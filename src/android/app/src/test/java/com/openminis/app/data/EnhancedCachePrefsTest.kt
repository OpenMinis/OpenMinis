package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-enhanced-cache] The Enhanced Cache toggle is now a durable
 * app-level preference instead of per-ViewModel memory. Its whole point is
 * that the chat menu, the usage screen and the provider request builder all
 * read the SAME value — this test pins that the observable flow and the
 * context-free read (which the provider layer consults at request-build time)
 * can never drift apart.
 */
class EnhancedCachePrefsTest {
    @Test
    fun `test seam keeps flow and cached read in sync`() {
        EnhancedCachePrefs.setEnabledForTest(true)
        assertTrue(EnhancedCachePrefs.isEnabled())
        assertTrue(EnhancedCachePrefs.enabled.value)

        EnhancedCachePrefs.setEnabledForTest(false)
        assertFalse(EnhancedCachePrefs.isEnabled())
        assertFalse(EnhancedCachePrefs.enabled.value)
    }
}
