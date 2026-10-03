package com.openminis.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import java.util.Locale

/**
 * Pre-Tiramisu locale override.
 *
 * On Android 13+ (`LocaleManager.applicationLocales`) the system propagates
 * the app-level locale to every Activity/Service Resources, including the
 * framework strings the system TextView uses to label its selection
 * `ActionMode` ("Cut" / "Copy" / "Paste" / "Select All"). On Android 12
 * and earlier — e.g. a MIUI 13 device from a field report —
 * there is no such system plumbing, so the text-selection ActionMode
 * stays in the device's system language regardless of the user's pick in
 * Settings → Appearance → Language.
 *
 * The fix is to apply the saved language to the base Configuration of
 * every user-facing Activity in `attachBaseContext`, which is what every
 * pre-Tiramisu "in-app language switch" library (e.g. YariKsiyev/AndroidLocalizer)
 * does. We don't need a library — one helper is enough.
 *
 * On Tiramisu+ this is a no-op so we don't double-apply.
 */
object LocaleWrap {

    /**
     * Returns a Context whose Configuration.locales contains the saved
     * language as the primary locale. Safe to call from
     * `Activity.attachBaseContext` (uses raw SharedPreferences on the
     * provided base context — must not depend on Application state).
     *
     * When the saved language is empty (= "Follow system") or we're on
     * API 33+, returns [base] unchanged.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val code = base
            .getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "")
            .orEmpty()
        if (code.isEmpty()) return base
        val locale = parseLocale(code) ?: return base
        val config = Configuration(base.resources.configuration)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = LocaleList(locale)
            LocaleList.setDefault(list)
            config.setLocales(list)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Parse a BCP-47-ish language tag the way our settings screen stores
     * it (just `en`, `zh`, `ja`, etc). For `zh-Hant` style tags we fall
     * back to `Locale.forLanguageTag`.
     */
    private fun parseLocale(code: String): Locale? {
        if (code.isBlank()) return null
        return if ('-' in code || '_' in code) {
            Locale.forLanguageTag(code.replace('_', '-'))
        } else {
            Locale(code)
        }
    }
}
