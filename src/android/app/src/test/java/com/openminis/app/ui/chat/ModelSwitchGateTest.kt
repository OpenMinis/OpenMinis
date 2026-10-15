package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-switch-next-request] Contract of the epoch gate that
 * stops a run's fallback writeback from clobbering a user's mid-run model
 * switch.
 *
 * The race (see ChatViewModel.modelSelectionEpoch): a run is streaming on
 * model A; A fails and group-fallback resolves model B; while the run's
 * tail is about to persist B onto the VM, the user opens the picker and
 * selects model C. The old code wrote unconditionally — the VM (and the
 * persisted binding) ended up on B, and C silently vanished.
 *
 * The gate: every user-driven model selection bumps a monotonic epoch;
 * runAgentLoop snapshots the epoch at its start and the fallback writeback
 * may only land while the snapshot is still current. The run itself keeps
 * executing on B (snapshot semantics); C takes effect on the NEXT request.
 */
class ModelSwitchGateTest {

    @Test
    fun `a writeback from the current epoch lands`() {
        // Normal fallback: no user selection happened between the run's
        // start and its tail's writeback — the fallback model persists.
        assertTrue(ModelSwitchGate.mayWriteBack(snapshottedEpoch = 3, currentEpoch = 3))
    }

    @Test
    fun `a writeback from a stale epoch is dropped`() {
        // The user picked a model mid-run (epoch bumped 3 → 4): the run's
        // fallback writeback must NOT clobber the user's selection.
        assertFalse(ModelSwitchGate.mayWriteBack(snapshottedEpoch = 3, currentEpoch = 4))
    }

    @Test
    fun `epoch zero is a valid snapshot`() {
        // First run after process start (epoch 0) must still be able to
        // write back — the gate must not treat 0 as "unset".
        assertTrue(ModelSwitchGate.mayWriteBack(snapshottedEpoch = 0, currentEpoch = 0))
    }

    @Test
    fun `an older snapshot never resurrects`() {
        // Even a much older snapshot (several user selections since) is
        // rejected — the comparison is exact equality, not ordering.
        assertFalse(ModelSwitchGate.mayWriteBack(snapshottedEpoch = 1, currentEpoch = 9))
    }
}
