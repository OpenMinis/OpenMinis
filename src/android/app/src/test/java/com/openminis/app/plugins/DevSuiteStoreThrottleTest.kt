package com.openminis.app.plugins

import com.openminis.app.plugins.DevSuiteStore.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-dev-suite-prefs-storm] Contract for the SharedPreferences write throttle.
 *
 * The installer feeds every apt/pip output line through
 * [DevSuiteStore.update] — hundreds of full-XML prefs rewrites per install
 * used to queue disk writes that QueuedWork.waitToFinish() could later force
 * the MAIN thread to drain (the install-crash amplifier). The contract:
 * only STATUS transitions persist; progress lines are in-memory only.
 *
 * Pure functions only (repo convention: framework entry points are
 * documented trade-offs, not unit-test targets).
 */
class DevSuiteStoreThrottleTest {

    // -- shouldPersistComponentState --

    @Test
    fun `progress-line refreshes with the same status never persist`() {
        // The apt/pip line stream: same status, new line, hundreds of times.
        assertFalse(DevSuiteStore.shouldPersistComponentState(Status.INSTALLING, Status.INSTALLING))
        assertFalse(DevSuiteStore.shouldPersistComponentState(Status.INSTALLED, Status.INSTALLED))
        assertFalse(DevSuiteStore.shouldPersistComponentState(Status.FAILED, Status.FAILED))
        assertFalse(DevSuiteStore.shouldPersistComponentState(Status.NOT_INSTALLED, Status.NOT_INSTALLED))
    }

    @Test
    fun `status transitions always persist`() {
        // First write (nothing known yet), every real transition, and the
        // purge path back to NOT_INSTALLED.
        assertTrue(DevSuiteStore.shouldPersistComponentState(null, Status.INSTALLING))
        assertTrue(DevSuiteStore.shouldPersistComponentState(Status.INSTALLING, Status.INSTALLED))
        assertTrue(DevSuiteStore.shouldPersistComponentState(Status.INSTALLING, Status.FAILED))
        assertTrue(DevSuiteStore.shouldPersistComponentState(Status.FAILED, Status.INSTALLING))
        assertTrue(DevSuiteStore.shouldPersistComponentState(Status.INSTALLED, Status.NOT_INSTALLED))
    }

    // -- encode / decode round trip --

    @Test
    fun `encode decode round trip preserves every field`() {
        val raw = DevSuiteStore.encodeComponentState(Status.INSTALLING, "apt 42% (Reading database…)", 1_728_950_000_000L)
        val state = DevSuiteStore.decodeComponentState("nodejs-suite", raw)!!

        assertEquals("nodejs-suite", state.componentId)
        assertEquals(Status.INSTALLING, state.status)
        assertEquals("apt 42% (Reading database…)", state.progressLine)
        assertEquals(1_728_950_000_000L, state.updatedAtMs)
    }

    @Test
    fun `decode tolerates absent and corrupt rows without throwing`() {
        assertNull(DevSuiteStore.decodeComponentState("x", null))

        // Corrupt/legacy row: unknown status token falls back to
        // NOT_INSTALLED, missing timestamp decodes as 0 — snapshot() must
        // never throw on prefs written by an older build.
        val state = DevSuiteStore.decodeComponentState("x", "GARBAGE")!!
        assertEquals(Status.NOT_INSTALLED, state.status)
        assertEquals("", state.progressLine)
        assertEquals(0L, state.updatedAtMs)
    }

    @Test
    fun `decode degrades gracefully when a line contains the separator`() {
        // split(limit = 3) keeps the remainder in the LAST field, so a
        // progress line that itself contains \u0001 swallows the timestamp
        // (toLongOrNull → null → 0). The status stays intact and nothing
        // throws — that is the pinned contract: prefs corruption degrades,
        // never crashes snapshot().
        val raw = "INSTALLING\u0001line\u0001with\u0001sep\u0001123"
        val state = DevSuiteStore.decodeComponentState("x", raw)!!
        assertEquals(Status.INSTALLING, state.status)
        assertEquals("line", state.progressLine)
        assertEquals(0L, state.updatedAtMs)
    }
}
