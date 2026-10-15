package com.openminis.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-orphan-notification-sweep] Contract tests for the pure decision core.
 *
 * Pins the semantics that make the process-start sweep safe:
 * - only rows on the listed process-bound channels are swept;
 * - every other channel (FGS banner, scheduled-task completion, …) survives;
 * - malformed rows (null channel) are never swept;
 * - empty channel list is a no-op (guards the vararg entry point).
 */
class OrphanNotificationSweepTest {

    private fun row(id: Int, channel: String?) =
        OrphanNotificationSweep.ActiveRow(id, channel)

    @Test
    fun sweepsOnlyListedChannels() {
        val active = listOf(
            row(11, AskUserNotifier.CHANNEL_ID),
            row(22, ApprovalNotifier.CHANNEL_ID),
            row(33, "minis_foreground"),
            row(44, "minis_scheduled"),
        )
        val ids = OrphanNotificationSweep.idsToSweep(
            active,
            setOf(AskUserNotifier.CHANNEL_ID, ApprovalNotifier.CHANNEL_ID),
        )
        assertEquals(listOf(11, 22), ids)
    }

    @Test
    fun leavesOtherChannelsUntouched() {
        val active = listOf(
            row(33, "minis_foreground"),
            row(44, "minis_scheduled"),
            row(55, "minis_question_other_app_lookalike"),
        )
        val ids = OrphanNotificationSweep.idsToSweep(
            active,
            setOf(AskUserNotifier.CHANNEL_ID),
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun neverSweepsNullChannelRows() {
        val active = listOf(row(66, null))
        val ids = OrphanNotificationSweep.idsToSweep(
            active,
            setOf(AskUserNotifier.CHANNEL_ID, ApprovalNotifier.CHANNEL_ID),
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun emptyChannelSetIsNoOp() {
        val active = listOf(row(11, AskUserNotifier.CHANNEL_ID))
        val ids = OrphanNotificationSweep.idsToSweep(active, emptySet())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun duplicateIdsAreAllReported() {
        // Two rows with the same id (reposted notification) — both match;
        // the shell's cancel() is idempotent so this is harmless.
        val active = listOf(
            row(11, AskUserNotifier.CHANNEL_ID),
            row(11, AskUserNotifier.CHANNEL_ID),
        )
        val ids = OrphanNotificationSweep.idsToSweep(
            active,
            setOf(AskUserNotifier.CHANNEL_ID),
        )
        assertEquals(listOf(11, 11), ids)
    }
}
