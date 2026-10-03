package com.openminis.app.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-android-restore-perf] Measures the importer's insert pattern on the real
 * device, batched against unbatched.
 *
 * Field measurement this reproduces: a Pixel 4a restoring 202,705 messages ran
 * at 119 msg/s (8.4 ms each) with the app at ~135% CPU — flash commit latency,
 * because every insert was its own implicit transaction.
 *
 * Uses a real on-disk Room database, not in-memory: the whole point is fsync
 * cost, and an in-memory database has none, which would make both arms look
 * identically fast and prove nothing.
 */
@RunWith(AndroidJUnit4::class)
class RestoreInsertBenchmark {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context

    /** Enough rows to dominate fixed costs, few enough to finish unbatched. */
    private val n = 2_000

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("bench.db")
        db = Room.databaseBuilder(ctx, AppDatabase::class.java, "bench.db").build()
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase("bench.db")
    }

    private fun session(i: Int) = ChatSessionEntity(
        id = "s$i", title = "t$i", modelId = "m",
        createdAt = 1L, updatedAt = 1L, category = null, lastMessage = null,
        modelBinding = null, source = null, memoryEnabled = 1, pinnedAt = null,
        editCount = 0, thinkingOverride = null, folderId = null,
        parentSessionId = null, parentToolUseId = null,
    )

    private fun message(i: Int, sid: String) = MessageEntity(
        id = "m$i", sessionId = sid, role = "user",
        partsJson = """[{"type":"text","text":"message body $i padded for realism"}]""",
        createdAt = i.toLong(), tokenUsage = null, sortOrder = i,
        reasoningContent = null, streamInterruptCount = 0, updatedAt = i.toLong(),
        errorInfo = null,
    )

    @Test
    fun batched_insert_is_dramatically_faster() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(session(0))

        // Arm A: the OLD pattern — one implicit transaction per row.
        val unbatchedMs = kotlin.system.measureTimeMillis {
            for (i in 0 until n) dao.insertMessage(message(i, "s0"))
        }

        // Clear, then Arm B: the NEW pattern — one transaction for all rows.
        dao.deleteMessages("s0")
        val batchedMs = kotlin.system.measureTimeMillis {
            db.withTransaction { for (i in 0 until n) dao.insertMessage(message(i, "s0")) }
        }

        val unbatchedRate = n * 1000.0 / unbatchedMs
        val batchedRate = n * 1000.0 / batchedMs
        val speedup = unbatchedMs.toDouble() / batchedMs

        android.util.Log.i("RestoreBench", "=== RESTORE INSERT BENCHMARK (n=$n) ===")
        android.util.Log.i("RestoreBench", "unbatched=${unbatchedMs}ms rate=${"%.0f".format(unbatchedRate)}/s per=${"%.2f".format(unbatchedMs.toDouble() / n)}ms")
        android.util.Log.i("RestoreBench", "batched=${batchedMs}ms rate=${"%.0f".format(batchedRate)}/s per=${"%.2f".format(batchedMs.toDouble() / n)}ms")
        android.util.Log.i("RestoreBench", "SPEEDUP=${"%.1f".format(speedup)}x")

        assertEquals("both arms must write every row", n, dao.messageCountForSession("s0"))
        assertTrue(
            "batching must be at least 2x faster (goal); measured ${"%.1f".format(speedup)}x",
            speedup >= 2.0,
        )
    }

    /**
     * [T-android-restore-perf] The OTHER half of the fix: the per-message
     * parent check.
     *
     * The importer ran `dao.getSession(sessionId)` once per message — 202,705
     * queries on the measured package — where the answer was already in
     * `restoredSessionIds`. This measures a query-per-row against a set lookup
     * per row, both inside one transaction so the fsync cost the other test
     * covers cannot mask the difference.
     */
    @Test
    fun set_lookup_beats_a_query_per_message() = runBlocking {
        val dao = db.chatDao()
        val ids = (0 until 200).map { "s$it" }
        db.withTransaction { ids.forEach { dao.insertSession(session(it.removePrefix("s").toInt())) } }
        val known = ids.toHashSet()

        // Arm A: a DB round trip per message, the old shape.
        val queryMs = kotlin.system.measureTimeMillis {
            db.withTransaction {
                for (i in 0 until n) {
                    val sid = ids[i % ids.size]
                    if (dao.getSession(sid) == null) continue
                }
            }
        }
        // Arm B: the in-memory set, the new shape.
        var hits = 0
        val setMs = kotlin.system.measureTimeMillis {
            for (i in 0 until n) {
                val sid = ids[i % ids.size]
                if (sid !in known) continue
                hits++
            }
        }
        val speedup = if (setMs == 0L) Double.POSITIVE_INFINITY else queryMs.toDouble() / setMs
        android.util.Log.i("RestoreBench", "parentCheck query=${queryMs}ms set=${setMs}ms " +
            "speedup=${if (speedup.isInfinite()) ">1000" else "%.0f".format(speedup)}x")
        assertEquals(n, hits)
        assertTrue("the set lookup must be faster", setMs <= queryMs)
    }
}
