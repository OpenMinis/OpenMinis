package com.openminis.app.inbound

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [inbound-task-api] The switch defaults off and the token rotates in place. */
@RunWith(RobolectricTestRunner::class)
class InboundTaskSettingsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `off by default and the token stays until it is regenerated`() {
        val settings = InboundTaskSettings(context)
        assertFalse(settings.isEnabled())
        val first = settings.token()
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(first, InboundTaskSettings(context).token())

        settings.setEnabled(true)
        assertTrue(InboundTaskSettings(context).isEnabled())

        val rotated = settings.regenerateToken()
        assertNotEquals(first, rotated)
        assertEquals(rotated, InboundTaskSettings(context).token())
        assertEquals(64, rotated.length)
    }
}
