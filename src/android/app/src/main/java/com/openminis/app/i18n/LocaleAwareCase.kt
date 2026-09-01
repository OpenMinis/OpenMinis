package com.openminis.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * [T-android-turkish-dotted-i] Uppercase a *display* string in the locale the
 * UI is actually being rendered in.
 *
 * `String.uppercase()` with no argument uses `Locale.getDefault()`, which is
 * the DEVICE locale — not the app's. Two things go wrong with that here:
 *
 *  1. Turkish has a dotted/dotless i pair. Under any non-Turkish locale
 *     "etkinliği".uppercase() yields "ETKINLIĞI", where correct Turkish is
 *     "ETKİNLİĞİ" — every section header containing an i renders subtly wrong.
 *     Measured on this app's strings: 96 of 147 header-ish keys contain i or ı.
 *  2. The app has an in-app language picker, so the UI locale routinely differs
 *     from the device locale. A Turkish user on a Chinese phone would get the
 *     device's rules applied to Turkish text.
 *
 * The inverse hazard is just as real: uppercasing English "id" under a Turkish
 * default gives "İD". Reading the locale from the composition's Configuration
 * — which the per-app locale already drives — makes the operation follow the
 * text being displayed, in both directions.
 *
 * Use this for anything the user reads. Do NOT use it for identifiers,
 * protocol tokens, or values that get compared or persisted: those must stay
 * locale-INVARIANT and should use `uppercase(Locale.ROOT)`.
 */
@Composable
fun String.uppercaseForDisplay(): String {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        @Suppress("DEPRECATION")
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    return remember(this, locale) { uppercase(locale) }
}
