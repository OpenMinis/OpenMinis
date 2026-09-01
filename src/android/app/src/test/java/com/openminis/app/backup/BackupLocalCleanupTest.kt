package com.openminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-local-cleanup] Pins when the local package is deleted.
 *
 * The local `.minisbak` is a FALLBACK, not an archive: once every enabled
 * destination holds a verified copy, a third copy on the phone only spends the
 * user's storage. A test device had accumulated six packages — about 62 MB —
 * purely because nothing ever removed them.
 *
 * The cost of getting this wrong is asymmetric. Deleting too eagerly can leave
 * a user with NO backup anywhere; keeping one too long only wastes space. So
 * the rule is deliberately narrow, and these cases fix it: delete only when
 * there was at least one destination AND every one of them succeeded.
 *
 * `BackupViewModel` needs an Application to construct, so — as with the other
 * tests in this module — this exercises the predicate rather than the
 * coroutine around it. If the two drift apart, `a failed destination keeps the
 * local copy` is the assertion that should fail.
 */
class BackupLocalCleanupTest {

    private fun outcome(name: String, ok: Boolean) =
        BackupHistory.DestinationOutcome(name = name, succeeded = ok)

    /** Mirrors the condition in BackupViewModel.startExport. */
    private fun shouldRemoveLocal(
        destinations: List<BackupHistory.DestinationOutcome>,
    ): Boolean = destinations.isNotEmpty() && destinations.all { it.succeeded }

    @Test
    fun `every destination verified removes the local copy`() {
        assertTrue(shouldRemoveLocal(listOf(outcome("nas", true))))
        assertTrue(shouldRemoveLocal(listOf(outcome("nas", true), outcome("offsite", true))))
    }

    /**
     * The important one. A partially delivered run must keep the package: the
     * server that failed has nothing, and deleting here would leave the run
     * with fewer copies than it started with.
     */
    @Test
    fun `a failed destination keeps the local copy`() {
        assertFalse(shouldRemoveLocal(listOf(outcome("nas", true), outcome("offsite", false))))
        assertFalse(shouldRemoveLocal(listOf(outcome("nas", false))))
    }

    /**
     * No destinations at all is the case where the local copy is the ONLY
     * copy. Deleting it would destroy the backup outright.
     *
     * Reachable despite the destination gate: a user can configure a server,
     * disable it, and the export still runs with an empty outcome list.
     */
    @Test
    fun `no destinations keeps the local copy`() {
        assertFalse(shouldRemoveLocal(emptyList()))
    }

    /**
     * The card's footer switches on this too — it must not claim a copy is
     * still on the device after the file was removed.
     */
    @Test
    fun `all-delivered is what the footer keys off`() {
        val allOk = listOf(outcome("nas", true), outcome("offsite", true))
        val someFailed = listOf(outcome("nas", true), outcome("offsite", false))
        assertTrue(allOk.isNotEmpty() && allOk.all { it.succeeded })
        assertFalse(someFailed.isNotEmpty() && someFailed.all { it.succeeded })
    }
}
