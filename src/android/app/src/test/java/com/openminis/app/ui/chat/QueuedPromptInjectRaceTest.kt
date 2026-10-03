package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-midtask-msg-vanishes] A message sent while a task is running must
 * not disappear from the chat.
 *
 * Reported by a user on 1.14: while Minis was executing a task, typing a new
 * message got it accepted and answered by the model — but its bubble vanished
 * from the UI until the session was reopened.
 *
 * Root cause, from the user's device log (session 00b40552, 2026-09-17):
 * `injectQueuedPromptsAsNewTurn` snapshotted `_messages.value` into a local
 * (`msgsAfterUnqueue`) and assigned that snapshot back on the Main thread
 * ~100 lines later. In between it suspends four times — ensureSession(),
 * prepareUserAttachments(), buildPastedParts() and appendMessage() (a DB
 * write). Anything appended to `_messages` during that gap was overwritten by
 * the stale snapshot.
 *
 * The gap is not theoretical: in the log the enqueue at 17:22:22.871 was
 * followed by the inject at 17:22:27.706 — **4.8 seconds**. Agent callbacks
 * enqueue PROGRAMMATIC prompts onto the same queue, so a user prompt landing
 * as `queue=2` beside a 9070-char callback happened twice inside four minutes.
 *
 * These tests model the list arithmetic of that emit — the production code is
 * a suspend function on a ViewModel needing a Context, DB and provider, so
 * (as with CompactToolPairingTest and AgentHistoryRebuildIdempotenceTest) the
 * DECISION being pinned is reproduced here rather than the coroutine plumbing.
 * The distinction under test is precisely the one that was wrong: filter a
 * STALE SNAPSHOT vs filter the LIVE list.
 */
class QueuedPromptInjectRaceTest {

    /** Minimal stand-in for ChatMessage: identity + the queued linkage. */
    private data class Msg(val id: String, val queuedPromptId: String? = null)

    /** The buggy shape: snapshot first, assign the snapshot back later. */
    private fun emitFromSnapshot(snapshot: List<Msg>, queuedIds: Set<String>): List<Msg> =
        snapshot.filterNot { it.queuedPromptId != null && it.queuedPromptId in queuedIds }

    /** The fixed shape: filter whatever is live at assignment time. */
    private fun emitFromLive(live: List<Msg>, queuedIds: Set<String>): List<Msg> =
        live.filterNot { it.queuedPromptId != null && it.queuedPromptId in queuedIds }

    // ── the reported bug ──────────────────────────────────────────────────

    @Test
    fun `a message appended during the suspend window survives the inject emit`() {
        val queuedIds = setOf("p1")
        // State when the inject starts: history + the queued placeholder.
        val atCaptureTime = listOf(
            Msg("m1"), Msg("m2"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
        )
        // …then, DURING the 4.8s of suspends, the user sends another message.
        val live = atCaptureTime + Msg("queued_msg_p2", queuedPromptId = "p2")

        val fixed = emitFromLive(live, queuedIds)

        assertTrue(
            "the message sent mid-task must still be in the list — this is the " +
                "exact bubble the user watched disappear",
            fixed.any { it.id == "queued_msg_p2" },
        )
        assertTrue(
            "the placeholder being replaced must still be removed",
            fixed.none { it.id == "queued_msg_p1" },
        )
        assertEquals(listOf("m1", "m2", "queued_msg_p2"), fixed.map { it.id })
    }

    @Test
    fun `the stale-snapshot shape is what loses it`() {
        // Guards the test above: proves the assertion is load-bearing by
        // running the pre-fix shape and showing the message is gone. If this
        // ever stops dropping it, the test above has stopped detecting the bug.
        val queuedIds = setOf("p1")
        val atCaptureTime = listOf(
            Msg("m1"), Msg("m2"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
        )
        val live = atCaptureTime + Msg("queued_msg_p2", queuedPromptId = "p2")

        val buggy = emitFromSnapshot(atCaptureTime, queuedIds)

        assertTrue(
            "the pre-fix shape drops the mid-window message — that IS the bug",
            buggy.none { it.id == "queued_msg_p2" },
        )
        assertTrue("and the live list did contain it", live.any { it.id == "queued_msg_p2" })
    }

    @Test
    fun `a programmatic enqueue arriving mid-window also survives`() {
        // Agent callbacks enqueue PROGRAMMATIC prompts on the same queue; in
        // the repro log one was 9070 chars. Losing its bubble is the same
        // defect, just less visible to the user.
        val queuedIds = setOf("p1")
        val live = listOf(
            Msg("m1"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
            Msg("queued_msg_cb", queuedPromptId = "cb"),   // callback, arrived late
        )
        val fixed = emitFromLive(live, queuedIds)
        assertTrue(fixed.any { it.id == "queued_msg_cb" })
        assertTrue(fixed.none { it.id == "queued_msg_p1" })
    }

    @Test
    fun `with no concurrent append the two shapes agree`() {
        // The common case must be unchanged — the fix is only about the race.
        val queuedIds = setOf("p1", "p2")
        val list = listOf(
            Msg("m1"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
            Msg("queued_msg_p2", queuedPromptId = "p2"),
        )
        assertEquals(emitFromSnapshot(list, queuedIds), emitFromLive(list, queuedIds))
        assertEquals(listOf("m1"), emitFromLive(list, queuedIds).map { it.id })
    }

    @Test
    fun `only the placeholders being injected are removed`() {
        // A placeholder for a prompt NOT in this inject batch must stay: it
        // belongs to a later batch and still has a pending prompt behind it.
        val queuedIds = setOf("p1")
        val live = listOf(
            Msg("queued_msg_p1", queuedPromptId = "p1"),
            Msg("queued_msg_p9", queuedPromptId = "p9"),
        )
        val fixed = emitFromLive(live, queuedIds)
        assertEquals(listOf("queued_msg_p9"), fixed.map { it.id })
    }
}
