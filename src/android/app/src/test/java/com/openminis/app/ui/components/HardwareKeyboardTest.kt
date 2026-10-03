package com.openminis.app.ui.components

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-hwkeyboard-virtual-false-positive] The hardware-keyboard rule
 * must not be satisfied by a VIRTUAL alphabetic device.
 *
 * Reported on a Pixel 4a: after sending a message the on-screen keyboard
 * stayed up. The composer's "keep focus with a hardware keyboard" gate
 * (cdf61e8ff) read `Configuration.keyboard == KEYBOARD_QWERTY`, and a
 * `scrcpy --keyboard=uhid` session had registered a virtual HID keyboard
 * (`bus=0x0006`, `Classes: KEYBOARD | ALPHAKEY`, `Generic.kcm`), which is
 * enough for Android to report `qwerty/v/v`. So a touch-only phone was
 * treated as having physical keys, and `releaseComposerAfterSend()` skipped
 * the IME dismiss.
 *
 * These pin the per-device predicate against the device inventory captured
 * from that phone's `dumpsys input`, so each real device on it lands on the
 * intended side.
 */
class HardwareKeyboardTest {

    private val ALPHA = InputDevice.KEYBOARD_TYPE_ALPHABETIC
    private val NONE = InputDevice.KEYBOARD_TYPE_NONE
    private val NON_ALPHA = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC

    // ── the reported false positive ────────────────────────────────────────

    @Test
    fun `a scrcpy uhid keyboard does not count as a hardware keyboard`() {
        // bus=0x0006 virtual bus, UHID-created: alphabetic, not virtual in the
        // framework sense, but NOT external.
        assertFalse(
            HardwareKeyboard.qualifies(
                keyboardType = ALPHA, isVirtual = false, isExternal = false, sourcesIncludeKeyboard = true,
            ),
        )
    }

    // ── what MUST still count ──────────────────────────────────────────────

    @Test
    fun `a real Bluetooth or USB keyboard still counts`() {
        assertTrue(
            HardwareKeyboard.qualifies(
                keyboardType = ALPHA, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = true,
            ),
        )
    }

    // ── the rest of the Pixel 4a inventory, each excluded for its own reason ─

    @Test
    fun `the framework's built-in virtual keyboard is excluded`() {
        // "uinput-fpc (aka device 0 - built-in keyboard)" / "-1: Virtual"
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = true, isExternal = false, sourcesIncludeKeyboard = true))
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = true, isExternal = true, sourcesIncludeKeyboard = true))
    }

    @Test
    fun `built-in button devices are excluded`() {
        // gpio-keys (IsExternal: false), qpnp_pon, headset jack buttons.
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = false, isExternal = false, sourcesIncludeKeyboard = true))
        assertFalse(HardwareKeyboard.qualifies(NON_ALPHA, isVirtual = false, isExternal = false, sourcesIncludeKeyboard = true))
    }

    @Test
    fun `an external device without an alphabetic layout is excluded`() {
        // A game controller or TV remote: external, has KEYBOARD source bits,
        // but no letters — the user cannot type a message on it.
        assertFalse(HardwareKeyboard.qualifies(NON_ALPHA, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = true))
        assertFalse(HardwareKeyboard.qualifies(NONE, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = true))
    }

    @Test
    fun `a device with no keyboard source is excluded even if otherwise plausible`() {
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = false))
    }
}
