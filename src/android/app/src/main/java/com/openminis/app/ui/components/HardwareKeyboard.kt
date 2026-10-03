package com.openminis.app.ui.components

import android.content.Context
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * [T-android-hwkeyboard-virtual-false-positive] "Is a real, external
 * hardware keyboard attached?" — the question the composer and the terminal
 * both ask before deciding whether to keep focus / skip dismissing the IME.
 *
 * The previous answer was `Configuration.keyboard == KEYBOARD_QWERTY &&
 * hardKeyboardHidden == HARDKEYBOARDHIDDEN_NO`. That is what Android
 * computes from the set of attached input devices, and it counts ANY device
 * that presents an alphabetic key layout — including virtual ones. Measured
 * on a Pixel 4a with no keyboard in sight: a `scrcpy --keyboard=uhid`
 * session registers a uinput device (`bus=0x0006`, `Classes: KEYBOARD |
 * ALPHAKEY`, `Generic.kcm`), the Configuration flips to `qwerty/v/v`, and
 * from then on sending a message no longer dismissed the on-screen keyboard.
 * The hardware-keyboard commit (cdf61e8ff) had verified `nokeys` on a Pixel
 * 6 — one that happened not to be mirrored at the time.
 *
 * The user-visible contract of that feature is "your hands are on physical
 * keys, so keep the caret in the box". A virtual HID device from a screen
 * mirror, a game controller's alpha map, or a TV remote satisfies the
 * Configuration check without satisfying the contract. So the decision is
 * made from the devices themselves:
 *
 *   - [InputDevice.isVirtual] excludes the framework's own virtual keyboard
 *     (`uinput-fpc … built-in keyboard`, `-1: Virtual`).
 *   - [InputDevice.isExternal] (API 29) excludes built-in button devices
 *     (`gpio-keys`, `qpnp_pon`, headset jacks) that also carry KEYBOARD
 *     class bits.
 *   - KEYBOARD_TYPE_ALPHABETIC excludes DPAD-only remotes.
 *
 * A scrcpy UHID keyboard is `bus=0x0006` (virtual bus) and, being created by
 * the UHID driver rather than a physical transport, is NOT reported as
 * external — so it drops out here while a real Bluetooth / USB keyboard,
 * which IS external, still qualifies.
 *
 * Below API 29 `isExternal` does not exist, so the Configuration check is
 * kept as the fallback: it is the old behaviour, not a regression, on the
 * handful of API 26–28 devices.
 *
 * [rememberHasHardwareKeyboard] re-evaluates on device add/remove (so a
 * Bluetooth keyboard connecting mid-session is picked up, like the
 * Configuration approach was) and also when Configuration changes, which
 * covers a folding cover flipping `hardKeyboardHidden`.
 */
object HardwareKeyboard {

    /** Pure predicate over a device's reported properties; unit-testable. */
    fun qualifies(
        keyboardType: Int,
        isVirtual: Boolean,
        isExternal: Boolean,
        sourcesIncludeKeyboard: Boolean,
    ): Boolean =
        sourcesIncludeKeyboard &&
            keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            !isVirtual &&
            isExternal

    /**
     * Evaluate against the live device list. On API < 29 falls back to the
     * Configuration flags (the previous behaviour).
     */
    fun isAttached(context: Context, configuration: Configuration): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
                configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        }
        // A keyboard that is attached but folded shut (slider / cover) is not
        // one the user is typing on. Configuration is the only place that
        // state lives, so keep honouring it.
        if (configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) return false
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val d = InputDevice.getDevice(id) ?: continue
            val hasKeyboardSource = (d.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
            if (qualifies(d.keyboardType, d.isVirtual, d.isExternal, hasKeyboardSource)) return true
        }
        return false
    }
}

/**
 * Composable state for [HardwareKeyboard.isAttached] that recomposes when a
 * device is added/removed/changed or when the Configuration changes.
 */
@Composable
fun rememberHasHardwareKeyboard(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var attached by remember { mutableStateOf(HardwareKeyboard.isAttached(context, configuration)) }
    // Re-evaluate on configuration changes (fold/unfold, keyboardHidden).
    androidx.compose.runtime.LaunchedEffect(configuration) {
        attached = HardwareKeyboard.isAttached(context, configuration)
    }
    DisposableEffect(context) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
            override fun onInputDeviceRemoved(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
            override fun onInputDeviceChanged(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
        }
        im?.registerInputDeviceListener(listener, null)
        onDispose { im?.unregisterInputDeviceListener(listener) }
    }
    return attached
}
