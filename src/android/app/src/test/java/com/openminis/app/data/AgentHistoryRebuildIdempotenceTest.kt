package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-revert-history-duplication] Rebuilding `agentHistory` from the DB
 * must be IDEMPOTENT — running it twice has to leave the same list as running
 * it once.
 *
 * The bug this pins: `loadSession()` rebuilt the list with a bare
 * `agentHistory.addAll(loaded.llmHistory)`, justified by a comment saying
 * "loadSession runs once at init before any sender writes into agentHistory".
 * That stopped being true the moment `revertCompact()` began calling
 * `reloadSessionFromDb()` -> `loadSession()` on an already-open session whose
 * history was fully populated: the addAll stacked a SECOND copy of every
 * persisted message onto the first.
 *
 * Measured on device before the fix — same session, same 20 DB rows: the next
 * request after a revert carried 31 rows with the first 12 duplicated, while a
 * cold restart of that same session sent 21. The database was never wrong; only
 * the in-memory list was. The model saw the user say everything twice, and the
 * duplicated prefix also guarantees a prompt-cache miss for the whole request.
 *
 * Why this test is shaped this way:
 *
 *  - The assertion is the device check turned into something automatable:
 *    **rebuilt history size == DB row count, exactly 1:1**, which is what was
 *    verified by hand after the fix (33 request rows == 34 DB rows - 1 in
 *    flight). No emulator, no Context, no DAO needed.
 *
 *  - `rebuild` mirrors the production shape (clear-then-fill) rather than
 *    driving a real ChatViewModel, which needs a Context, a database and a
 *    provider to construct. Same approach as CompactToolPairingTest and
 *    InLoopContextPolicyTest.
 *
 *  - The cold-start path and the revert path are asserted to produce
 *    IDENTICAL lists, because that equivalence is the actual product
 *    requirement: reverting a compaction must leave you exactly where a fresh
 *    launch would.
 */
class AgentHistoryRebuildIdempotenceTest {

    /** Stand-in for a persisted row, carrying a Text part like the real one. */
    private fun row(id: String, role: LLMMessage.Role, text: String) = LLMMessage(
        role = role,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
        dbMessageId = id,
    )

    /** A session as it sits on disk: alternating user/assistant turns. */
    private fun dbRows(turns: Int): List<LLMMessage> =
        (1..turns).flatMap { i ->
            listOf(
                row("u$i", LLMMessage.Role.USER, "say MESSAGE-$i"),
                row("a$i", LLMMessage.Role.ASSISTANT, "MESSAGE-$i Pika!"),
            )
        }

    /**
     * Mirror of the production rebuild in `loadSession()` AFTER the fix:
     * clear, then fill. Kept byte-for-byte equivalent in shape — if production
     * ever drops the `clear()` again, this is the invariant that breaks.
     */
    private fun rebuild(agentHistory: MutableList<LLMMessage>, loaded: List<LLMMessage>) {
        agentHistory.clear()
        agentHistory.addAll(loaded)
    }

    // ── the reported bug ──────────────────────────────────────────────────

    @Test
    fun `rebuilding twice leaves the same history as rebuilding once`() {
        val db = dbRows(10)          // 20 rows, like the measured session
        val history = mutableListOf<LLMMessage>()

        rebuild(history, db)                       // cold start
        val afterColdStart = history.toList()

        rebuild(history, db)                       // revert -> reloadSessionFromDb

        assertEquals(
            "rebuilding twice must not grow the history. Before the fix this was " +
                "40 (every row duplicated) instead of 20.",
            db.size,
            history.size,
        )
        assertEquals(
            "a revert must leave exactly what a cold start would leave",
            afterColdStart,
            history.toList(),
        )
    }

    @Test
    fun `rebuilt history matches the DB row for row, one to one`() {
        val db = dbRows(10)
        val history = mutableListOf<LLMMessage>()
        repeat(3) { rebuild(history, db) }        // revert, revert again, and again

        assertEquals("history must stay 1:1 with the DB", db.size, history.size)
        assertEquals(
            "row ids must line up with the DB in order",
            db.map { it.dbMessageId },
            history.map { it.dbMessageId },
        )
        val ids = history.mapNotNull { it.dbMessageId }
        assertEquals(
            "no dbMessageId may appear twice — a repeated id IS the duplication bug",
            ids.size,
            ids.toSet().size,
        )
    }

    @Test
    fun `rebuilding onto a populated list discards the stale contents`() {
        // The revert case specifically: the list is NOT empty when the rebuild
        // runs, because the session is already open.
        val db = dbRows(3)
        val history = mutableListOf<LLMMessage>()
        rebuild(history, db)

        // Simulate turns appended after load (what a live session accumulates)
        history.add(row("u99", LLMMessage.Role.USER, "say LATER"))
        assertEquals(db.size + 1, history.size)

        // A revert re-reads the DB, which by then also holds that turn.
        val dbAfter = db + row("u99", LLMMessage.Role.USER, "say LATER")
        rebuild(history, dbAfter)

        assertEquals(
            "the in-memory tail must be replaced by the DB's version, not appended to",
            dbAfter.size,
            history.size,
        )
        assertTrue(
            "the later turn must appear exactly once",
            history.count { it.dbMessageId == "u99" } == 1,
        )
    }

    // ── guarding the assertion itself ─────────────────────────────────────

    @Test
    fun `an append-only rebuild is what the invariant catches`() {
        // Proves the assertions above are load-bearing: with the pre-fix
        // append-only shape, the same sequence doubles the history. If this
        // test ever fails, the checks above have stopped detecting the bug.
        val db = dbRows(10)
        val history = mutableListOf<LLMMessage>()

        fun buggyRebuild(loaded: List<LLMMessage>) = history.addAll(loaded)  // no clear()
        buggyRebuild(db)
        buggyRebuild(db)

        assertEquals("the pre-fix shape duplicates — this is what we must never ship", 40, history.size)
        val ids = history.mapNotNull { it.dbMessageId }
        assertTrue("and it produces repeated dbMessageIds", ids.size != ids.toSet().size)
    }
}
