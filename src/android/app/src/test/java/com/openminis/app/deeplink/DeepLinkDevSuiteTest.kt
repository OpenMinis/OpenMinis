package com.openminis.app.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-dev-suite] Parsing contract for `minis://devsuite/install/<bundleId>`.
 * The agent emits these links in chat; a malformed link must land on the
 * plugin market (best-effort, like every other settings path) rather than
 * dead-ending. Pure function under test — Uri.parse is null under
 * returnDefaultValues, so the Uri shell stays untested by design.
 */
class DeepLinkDevSuiteTest {

    @Test
    fun `valid bundle id parses to install action`() {
        val action = DeepLinkHandler.parseDevSuitePath("/install/android-suite")
        assertTrue(action is DeepLinkAction.OpenDevSuiteInstall)
        assertEquals("android-suite", (action as DeepLinkAction.OpenDevSuiteInstall).bundleId)
    }

    @Test
    fun `online tool id is also a valid install target`() {
        // The parser only shape-checks the id; what makes this link honest is
        // that the market resolves it through DevSuiteCatalog.installTargetById
        // — see DevSuiteCatalogTest."deep link ids resolve to a bundle or an
        // online tool". A tool id that parsed but resolved nowhere used to be
        // consumed silently, leaving the user on an opened screen with nothing
        // happening.
        val action = DeepLinkHandler.parseDevSuitePath("/install/llama-cpp")
        assertEquals("llama-cpp", (action as DeepLinkAction.OpenDevSuiteInstall).bundleId)
        assertNotNull(
            com.openminis.app.plugins.DevSuiteCatalog.installTargetById(action.bundleId),
        )
    }

    @Test
    fun `malformed path lands on plugin market`() {
        val expected = DeepLinkAction.OpenSettingsScreen("plugin_market")
        assertEquals(expected, DeepLinkHandler.parseDevSuitePath(""))
        assertEquals(expected, DeepLinkHandler.parseDevSuitePath("/install"))
        assertEquals(expected, DeepLinkHandler.parseDevSuitePath("/install/../../etc"))
        // Uppercase / symbols are rejected by the id pattern.
        assertEquals(expected, DeepLinkHandler.parseDevSuitePath("/install/AndroidSuite"))
        // Extra segments are not the install form.
        assertEquals(expected, DeepLinkHandler.parseDevSuitePath("/install/x/y"))
    }
}
