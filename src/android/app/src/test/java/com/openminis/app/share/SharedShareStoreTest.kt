package com.openminis.app.share

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T33] SharedShareStore: consecutive shares merge, TTL cleanup spares the live
 * buffer's files, and the merge is bounded.
 *
 * Pins cb5bdfdc8 (merge instead of replace, at BOTH the prefs layer and the
 * in-memory ShareCoordinator buffer), 3908e1f65 (`cleanSharedFiles(keep)` and
 * MERGE_MAX_ITEMS = 50), 5d83384cc / 3a0b0a821 (a persisted share is picked up
 * on any launch). These were only ever verified on a device; this is the JVM
 * counterpart with an in-memory SharedPreferences fake and a temp filesDir.
 */
class SharedShareStoreTest {

    // ── minimal fakes: only what SharedShareStore / ShareCoordinator touch ──

    private class FakePrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()

        inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeContext(private val files: File, private val prefs: FakePrefs) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
    }

    private lateinit var filesDir: File
    private lateinit var prefs: FakePrefs
    private lateinit var context: Context

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("share-store-test").toFile()
        prefs = FakePrefs()
        context = FakeContext(filesDir, prefs)
        SharedShareStore.clearPendingShare(context)
        // Drain any buffer a previous test left in the process-global coordinator.
        ShareCoordinator.consumeBuffer(context)
    }

    @After
    fun tearDown() {
        ShareCoordinator.consumeBuffer(context)
        filesDir.deleteRecursively()
    }

    private fun text(v: String) = PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, v)
    private fun attachment(name: String) = PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
    private fun share(vararg items: PendingShare.Item, ageMs: Long = 0) =
        PendingShare(items.toList(), System.currentTimeMillis() - ageMs)

    private fun stagedFile(name: String): File =
        File(SharedShareStore.sharedFileDirectory(context), name).apply { writeText("bytes of $name") }

    // ============================================================ prefs-layer merge

    /** A arrives, then B, before the app consumed A: both survive, in order. */
    @Test
    fun `a second share merges into the unconsumed first, in arrival order`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        SharedShareStore.savePendingShare(context, share(attachment("b.png")))
        val loaded = SharedShareStore.loadPendingShare(context)
        assertNotNull(loaded)
        assertEquals(listOf(text("A"), attachment("b.png")), loaded!!.items)
    }

    /** The same content delivered twice (a double-delivered intent) is stored once. */
    @Test
    fun `identical content shared twice is de-duplicated`() {
        SharedShareStore.savePendingShare(context, share(text("same"), attachment("x.png")))
        SharedShareStore.savePendingShare(context, share(text("same"), attachment("x.png"), text("new")))
        val items = SharedShareStore.loadPendingShare(context)!!.items
        assertEquals(listOf(text("same"), attachment("x.png"), text("new")), items)
    }

    /** Kind is part of the identity: the same string as text and as a filename are two items. */
    @Test
    fun `dedupe is keyed by kind and value`() {
        SharedShareStore.savePendingShare(context, share(text("note.txt")))
        SharedShareStore.savePendingShare(context, share(attachment("note.txt")))
        assertEquals(2, SharedShareStore.loadPendingShare(context)!!.items.size)
    }

    /** The merged record gets a fresh timestamp so the combined share has a full window. */
    @Test
    fun `merging renews the timestamp`() {
        SharedShareStore.savePendingShare(context, share(text("A"), ageMs = 200_000))
        val before = System.currentTimeMillis()
        SharedShareStore.savePendingShare(context, share(text("B")))
        assertTrue(SharedShareStore.loadPendingShare(context)!!.timestampMs >= before)
    }

    /** An abandoned record (older than 300s) is replaced, not merged. */
    @Test
    fun `a stale record is replaced instead of merged`() {
        SharedShareStore.savePendingShare(context, share(text("old"), ageMs = 301_000))
        SharedShareStore.savePendingShare(context, share(text("fresh")))
        assertEquals(listOf(text("fresh")), SharedShareStore.loadPendingShare(context)!!.items)
    }

    /** Merging is bounded at 50 items and keeps the NEWEST — the share the user just acted on. */
    @Test
    fun `merging past the cap drops the oldest items`() {
        SharedShareStore.savePendingShare(context, share(*(1..45).map { text("t$it") }.toTypedArray()))
        SharedShareStore.savePendingShare(context, share(*(46..55).map { text("t$it") }.toTypedArray()))
        val items = SharedShareStore.loadPendingShare(context)!!.items
        assertEquals(50, items.size)
        assertEquals("the oldest five are gone", text("t6"), items.first())
        assertEquals("the newest survives", text("t55"), items.last())
        assertFalse(items.contains(text("t1")))
    }

    @Test
    fun `clear removes the record and a missing record loads as null`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        SharedShareStore.clearPendingShare(context)
        assertNull(SharedShareStore.loadPendingShare(context))
    }

    // ============================================================ TTL cleanup spares the live buffer

    /** `keep` names the files a still-live buffer references; everything else goes. */
    @Test
    fun `cleanup exempts the files named in keep`() {
        val live = stagedFile("live-1.png")
        val live2 = stagedFile("live-2.png")
        val stale = stagedFile("stale.png")
        SharedShareStore.cleanSharedFiles(context, keep = setOf("live-1.png", "live-2.png"))
        assertTrue(live.exists())
        assertTrue(live2.exists())
        assertFalse(stale.exists())
        SharedShareStore.cleanSharedFiles(context)
        assertFalse("an unconditional clean removes the rest", live.exists())
    }

    /**
     * The coordinator path: share A is buffered in memory (holding file refs);
     * a STALE prefs record then takes the discard path. A's attachments must
     * survive that cleanup — this is the real bug 3908e1f65 fixed.
     */
    @Test
    fun `a stale record's cleanup does not delete the live buffer's attachments`() {
        val a = stagedFile("a-live.png")
        SharedShareStore.savePendingShare(context, share(attachment("a-live.png")))
        ShareCoordinator.processPendingShare(context)   // A is now the live buffer
        assertNull("the record was consumed into memory", SharedShareStore.loadPendingShare(context))

        val stale = stagedFile("stale.png")
        prefs.edit().putString("pending_share", share(attachment("stale.png"), ageMs = 400_000).toJson().toString()).apply()
        ShareCoordinator.processPendingShare(context)   // stale → discard path
        assertTrue("live buffer's file must survive", a.exists())
        assertFalse("the stale share's own file is cleaned", stale.exists())

        val consumed = ShareCoordinator.consumeBuffer(context)
        assertEquals(listOf(attachment("a-live.png")), consumed!!.items)
    }

    // ============================================================ in-memory buffer merge

    /** Two launches before the chat consumed the buffer: the coordinator appends, it does not replace. */
    @Test
    fun `the in-memory buffer merges consecutive launches`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        ShareCoordinator.processPendingShare(context)
        val v1 = ShareCoordinator.bufferVersion.value
        SharedShareStore.savePendingShare(context, share(text("B"), text("A")))
        ShareCoordinator.processPendingShare(context)
        assertEquals("a warm session must be told to inject", v1 + 1, ShareCoordinator.bufferVersion.value)
        val consumed = ShareCoordinator.consumeBuffer(context)
        assertEquals(listOf(text("A"), text("B")), consumed!!.items)
        assertNull("consume is one-shot", ShareCoordinator.consumeBuffer(context))
    }

    /** A stale record on launch is discarded and never buffered. */
    @Test
    fun `a stale persisted share is dropped on launch`() {
        SharedShareStore.savePendingShare(context, share(text("old"), ageMs = 400_000))
        ShareCoordinator.processPendingShare(context)
        assertNull(SharedShareStore.loadPendingShare(context))
        assertNull(ShareCoordinator.consumeBuffer(context))
    }
}
