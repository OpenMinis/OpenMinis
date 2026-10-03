package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-vm-store-leak] ChatViewModelStore is a process-level cache that
 * had no bound and only one eviction path — release(), called solely when a
 * session is DELETED. Navigating away never dropped anything, so every session
 * opened in a process lifetime stayed resident with its agent history and
 * rendered rows.
 *
 * A device log over ~3 hours recorded ten allocations and zero releases; heap
 * climbed 5% -> 85% of a 512MB cap and RSS to 3.3GB, after which almost every
 * allocation hit a blocking GC (~230/min, 1.4s stalls, 149-frame drops, hot
 * phone).
 *
 * The store is an Android-runtime object this JVM test cannot instantiate, so
 * these pin the invariants as source facts — enough to fail the build if the
 * bound or its two carve-outs are dropped.
 */
class ChatViewModelStoreEvictionTest {

    private val src: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        f.readText()
    }

    @Test
    fun `the cache is bounded`() {
        assertTrue(
            "ChatViewModelStore must declare a size cap — an unbounded cache of " +
                "ChatViewModels is the leak this fixes. " +
                "[T-android-vm-store-dual-pool] split into one cap per pool.",
            src.contains("MAX_CACHED_NORMAL") && src.contains("MAX_CACHED_CHILD"),
        )
        assertTrue(
            "ownerFor must trim after inserting, or the cap is never enforced",
            src.contains("trimToCapacity("),
        )
    }

    @Test
    fun `eviction order is least-recently-used, not insertion order`() {
        assertTrue(
            "backing map must be a LinkedHashMap so iteration order can carry " +
                "recency",
            src.contains("LinkedHashMap<String, ViewModelStore>()"),
        )
        assertTrue(
            "ownerFor must re-insert an existing store so a session the user " +
                "keeps returning to is not evicted ahead of one opened once and " +
                "abandoned",
            src.contains("val existing = stores.remove(key)"),
        )
    }

    @Test
    fun `a streaming session is never evicted`() {
        // Clearing a busy session's store cancels viewModelScope and kills the
        // agent loop mid-flight — precisely what this cache exists to prevent.
        // The cap is therefore soft: it may be exceeded rather than break a run.
        assertTrue(
            "trim must consult SessionActivityTracker.activeSessions",
            src.contains("SessionActivityTracker.activeSessions"),
        )
        assertTrue(
            "busy sessions must be excluded from the evictable set",
            src.contains("it !in busy"),
        )
    }

    @Test
    fun `the on-screen session is never evicted`() {
        assertTrue(
            "the session currently displayed must be excluded, or it would be " +
                "torn down underneath the composition reading it",
            src.contains("it != onScreen"),
        )
    }
}
