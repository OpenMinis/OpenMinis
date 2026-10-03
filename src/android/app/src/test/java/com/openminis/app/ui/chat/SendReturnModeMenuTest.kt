package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-send-longpress-return-mode] Long-pressing the composer Send button opens a
 * "Return key" chooser that writes the same `returnKeyBehavior` pref the
 * Appearance setting owns, and must work on the grey (nothing-to-send) button.
 */
class SendReturnModeMenuTest {

    @Test
    fun prefMappingRoundTripsAndMatchesAppearanceValues() {
        assertEquals(1, SendReturnMode.prefFromSends(true))
        assertEquals(0, SendReturnMode.prefFromSends(false))
        assertTrue(SendReturnMode.sendsFromPref(1))
        assertFalse(SendReturnMode.sendsFromPref(0))
        // Same rule as returnKeySendsMessage(): only exactly 1 means Send.
        assertFalse(SendReturnMode.sendsFromPref(2))
        assertFalse(SendReturnMode.sendsFromPref(-1))
        for (b in listOf(true, false)) {
            assertEquals(b, SendReturnMode.sendsFromPref(SendReturnMode.prefFromSends(b)))
        }
    }

    @Test
    fun tapOnGreyButtonIsNoOp() {
        assertFalse(SendReturnMode.tapShouldSend(canActivate = false, menuVisible = false, msSinceMenuDismiss = -1))
        assertFalse(SendReturnMode.tapShouldSend(canActivate = false, menuVisible = false, msSinceMenuDismiss = 10_000))
    }

    @Test
    fun tapSendsNormallyWhenSendable() {
        assertTrue(SendReturnMode.tapShouldSend(canActivate = true, menuVisible = false, msSinceMenuDismiss = -1))
        assertTrue(SendReturnMode.tapShouldSend(canActivate = true, menuVisible = false, msSinceMenuDismiss = 5_000))
    }

    @Test
    fun tapThatDismissesMenuDoesNotAlsoSend() {
        assertFalse(SendReturnMode.tapShouldSend(canActivate = true, menuVisible = true, msSinceMenuDismiss = -1))
        assertFalse(SendReturnMode.tapShouldSend(canActivate = true, menuVisible = false, msSinceMenuDismiss = 40))
        assertTrue(
            SendReturnMode.tapShouldSend(
                canActivate = true, menuVisible = false,
                msSinceMenuDismiss = SendReturnMode.DISMISS_TAP_GUARD_MS,
            ),
        )
    }

    @Test
    fun placementPrefersAboveRightAligned() {
        // 1080x2000 window, 38px button at the bottom-right, 400x200 popup.
        val (x, y) = SendReturnMode.placement(
            anchorLeft = 1000, anchorTop = 1800, anchorRight = 1038, anchorBottom = 1838,
            windowWidth = 1080, windowHeight = 2000,
            popupWidth = 400, popupHeight = 200, gapPx = 20,
        )
        assertEquals(638, x)
        assertEquals(1580, y)
    }

    @Test
    fun placementFlipsBelowWhenNoRoomAbove() {
        val (_, y) = SendReturnMode.placement(
            anchorLeft = 1000, anchorTop = 100, anchorRight = 1038, anchorBottom = 138,
            windowWidth = 1080, windowHeight = 2000,
            popupWidth = 400, popupHeight = 200, gapPx = 20,
        )
        assertEquals(158, y)
    }

    @Test
    fun pivotSitsOnAnchorCentre() {
        // Popup 638..1038 wide; button centre 1019 → near the right edge.
        assertEquals((1019 - 638) / 400f, SendReturnMode.pivotFraction(1019, 638, 400), 1e-6f)
        // Popup above the button: pivot y is below the popup (> 1) so the
        // scale grows out of the button, not the popup's own edge.
        assertTrue(SendReturnMode.pivotFraction(1819, 1580, 200) > 1f)
        assertEquals(0.5f, SendReturnMode.pivotFraction(10, 0, 0), 0f)
    }

    private val chatScreen = File("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt").readText()
    private val menuSrc = File("src/main/java/com/openminis/app/ui/chat/SendReturnModeMenu.kt").readText()

    @Test
    fun chatScreenUsesSendButtonWithLongPress() {
        assertTrue(chatScreen.contains("ComposerSendButton("))
        assertTrue(menuSrc.contains("onLongClick = { menuVisible = true }"))
        // Must not regress to a disabled clickable that swallows long-press.
        assertFalse(menuSrc.contains("clickable(enabled = canActivate)"))
        assertTrue(menuSrc.contains("CustomAccessibilityAction("))
    }

    @Test
    fun composerReadsReturnPrefObservably() {
        assertTrue(chatScreen.contains("val sendOnEnter by rememberReturnKeySendsMessage(context)"))
        assertFalse(chatScreen.contains(".returnKeySendsMessage(context)"))
        assertTrue(menuSrc.contains("registerOnSharedPreferenceChangeListener"))
        assertTrue(menuSrc.contains("KEY_RETURN_KEY_BEHAVIOR"))
    }

    @Test
    fun stopButtonHasNoLongPressChooser() {
        val stopIdx = chatScreen.indexOf("Icons.Default.Stop,")
        assertTrue(stopIdx > 0)
        val stopBlock = chatScreen.substring(chatScreen.lastIndexOf("if (showStop)", stopIdx), stopIdx)
        assertFalse(stopBlock.contains("combinedClickable"))
        assertFalse(stopBlock.contains("ComposerSendButton"))
    }
}
