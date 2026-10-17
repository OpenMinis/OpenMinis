package com.openminis.app.speech.correction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `correction_events` is regenerable learning signal, not an archive; it must
 * stay bounded by count and age. Each test opens its own file — the production
 * singleton would leak state across test methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceCorrectionPruneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: VoiceCorrectionDb

    @Before
    fun setUp() {
        db = VoiceCorrectionDb.openForTest(File(tmp.root, "voice-correction.db"))!!
        assertNotNull(db)
    }

    private fun record(n: Int) {
        repeat(n) { i ->
            db.recordCorrectionEvent(
                original = "hello $i",
                corrected = "hallo $i",
                outcome = "accepted",
                sourceSummary = null,
            )
        }
    }

    @Test
    fun eventsAreCappedToNewestRows() {
        record(5)
        val nowSec = System.currentTimeMillis() / 1000.0
        val deleted = db.pruneEvents(maxEvents = 2, maxAgeDays = 36_500, nowSec = nowSec)
        assertEquals(3, deleted)
        assertEquals(2, db.counts()["correction_events"])
    }

    @Test
    fun eventsOlderThanTheWindowAreDeleted() {
        record(1)
        val nowSec = System.currentTimeMillis() / 1000.0
        val deleted = db.pruneEvents(
            maxEvents = 100,
            maxAgeDays = 1,
            nowSec = nowSec + 2 * 86_400.0,
        )
        assertTrue("deleted=$deleted counts=${db.counts()}", deleted >= 1)
        assertEquals(0, db.counts()["correction_events"])
    }
}
