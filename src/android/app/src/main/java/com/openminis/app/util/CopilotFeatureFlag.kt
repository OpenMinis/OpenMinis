package com.openminis.app.util

import android.content.Context

/**
 * [T-copilot-provider] Local preferences for the GitHub Copilot provider.
 *
 * Once a kill switch that hid the provider from the Add Provider list; that
 * gate is retired (see [KEY]). What remains is the consent record — whether
 * the user has accepted the unofficial-access notice — so the warning is shown
 * once rather than on every sign-in.
 *
 * Named to match the iOS keys (`copilotProviderEnabled`,
 * `copilotSignInConsentAccepted`) so the two platforms are searchable together.
 */
object CopilotFeatureFlag {

    private const val PREFS = "feature_flags"

    /**
     * [T-android-copilot-row-consent-only] The old visibility switch. Nothing
     * reads it any more, and there is deliberately no accessor.
     *
     * The provider is now a plain row in the Add Provider list, exactly like
     * the other OAuth providers, and consent is taken where it belongs — the
     * disclaimer dialog, which stands between the user and the first network
     * call. A second gate here only made the user read and accept the same
     * warning twice, and its section occupied a permanent titled block of that
     * screen to control the visibility of a single row.
     *
     * The key stays DEFINED rather than deleted so a build that needs to
     * withdraw the entry point again has the name to hand, and so a device that
     * already stored the value is not left with an orphan whose meaning nobody
     * can look up. Mirrors the iOS treatment of `copilotProviderEnabled`.
     */
    @Suppress("unused")
    private const val KEY = "copilotProviderEnabled"

    /**
     * [T-android-copilot-consent-remembered] Whether the user has accepted the
     * unofficial-access notice.
     *
     * Persisted so the notice is shown ONCE. Re-asking someone who has already
     * accepted does not make the disclosure stronger — a screen that appears
     * every time is one the reader learns to tap past, which weakens the very
     * warning it is meant to deliver. The acceptance is a standing decision
     * about this integration, so it is recorded as one. Mirrors iOS
     * `CopilotConstants.hasAcceptedSignInConsent`.
     *
     * Deliberately NOT per provider instance: the notice is about the METHOD of
     * access (a client identity issued to another tool), not about which account
     * is signed in, so a second account raises nothing new.
     *
     * Deliberately in plain SharedPreferences, not the encrypted store: it is a
     * preference, not a secret, and it SHOULD reset on reinstall — a fresh
     * install is a fresh reader, and re-consenting once costs one tap.
     */
    private const val KEY_CONSENT = "copilotSignInConsentAccepted"

    fun hasAcceptedConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENT, false)

    fun recordConsent(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONSENT, true).apply()
    }
}
