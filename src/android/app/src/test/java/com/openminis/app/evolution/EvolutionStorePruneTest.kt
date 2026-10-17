package com.openminis.app.evolution

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bounded retention for the append-only diagnostics tables. Before this they
 * grew forever; `beliefs`/`proposals` stay untouched because they are the
 * user-visible learned state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EvolutionStorePruneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): EvolutionStore = EvolutionStore(File(tmp.root, "evolution")).also {
        // Touch the app context only so Robolectric initializes SQLite.
        ApplicationProvider.getApplicationContext<android.content.Context>()
    }

    private fun observe(store: EvolutionStore, n: Int) {
        repeat(n) { i ->
            store.insertObservation(
                kind = "test",
                sessionId = "s",
                skillId = null,
                evidence = "evidence-$i",
                fingerprint = "fp-$i",
            )
        }
    }

    @Test
    fun observationsAreCappedToNewestRows() {
        val store = store()
        observe(store, 6)
        val deleted = store.prune(
            maxObservations = 2,
            maxToolFails = 100,
            maxAgeMs = Long.MAX_VALUE / 4,
        )
        assertEquals(4, deleted)
    }

    @Test
    fun toolFailsAreCappedToNewestRows() {
        val store = store()
        repeat(5) { store.recordToolFail("s", "skill", "error-$it") }
        val deleted = store.prune(
            maxObservations = 100,
            maxToolFails = 1,
            maxAgeMs = Long.MAX_VALUE / 4,
        )
        assertEquals(4, deleted)
    }

    @Test
    fun rowsOlderThanTheRetentionWindowAreDeleted() {
        val store = store()
        observe(store, 1)
        val deleted = store.prune(
            maxObservations = 100,
            maxToolFails = 100,
            maxAgeMs = 1L,
            now = System.currentTimeMillis() + 10_000,
        )
        assertTrue("old row must be pruned", deleted >= 1)
    }
}
