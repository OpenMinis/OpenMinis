package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-resume-banner-race] Contract of the epoch gate that stops the
 * resume banner from coming back over a fresh turn.
 *
 * The race (see ChatViewModel.resumeEpoch): the user stops a run, sends a
 * new message (sendMessage clears _canResume and waits for the old run's
 * concurrency slot), and the old run's winding-down tail then executes its
 * own `_canResume = true` — unconditionally, so the banner reappears over
 * the conversation that is already streaming a fresh turn.
 *
 * The gate: every send/resume/retry/queue-continuation bumps a monotonic
 * epoch; every raiser snapshots the epoch when its run starts and may only
 * raise while the snapshot is still current.
 */
class ResumeBannerRaceTest {

    @Test
    fun `a raise from the current epoch lands`() {
        // Normal stop: no newer send happened between the run's start and
        // its tail's raise — the banner must appear.
        assertTrue(ResumeBannerRace.mayRaise(snapshottedEpoch = 3, currentEpoch = 3))
    }

    @Test
    fun `a raise from a stale epoch is dropped`() {
        // The raced case: the old run started at epoch 3, the user sent a
        // new message (epoch bumped to 4) while the old tail was still
        // winding down — the tail's raise must NOT land.
        assertFalse(ResumeBannerRace.mayRaise(snapshottedEpoch = 3, currentEpoch = 4))
    }

    @Test
    fun `epoch never rewinds so an older raise can never become current again`() {
        // Even a much later epoch (several sends later) must reject a raise
        // snapshotted before all of them.
        assertFalse(ResumeBannerRace.mayRaise(snapshottedEpoch = 3, currentEpoch = 9))
    }

    @Test
    fun `epoch zero is a valid snapshot for the very first run`() {
        // The initial epoch is 0 and the first run's snapshot is 0 — a
        // legitimate raise must not be rejected just because the counter
        // started at zero.
        assertTrue(ResumeBannerRace.mayRaise(snapshottedEpoch = 0, currentEpoch = 0))
    }
}
