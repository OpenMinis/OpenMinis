package com.openminis.app.accessibility

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restricted-settings] Install-source classification.
 *
 * [RestrictedSettingsManager.isRestrictedSource] is deliberately pure so it
 * can run on the JVM — this module has no Robolectric, so `isRestricted`
 * itself (Context + PackageManager) is untestable here. The classification is
 * the part with real risk in both directions: a false positive puts a "your
 * install is blocked by Android" section in front of a user whose install is
 * fine, and a false negative leaves the reporting user staring at a
 * greyed-out toggle with no explanation.
 *
 * The values mirror AOSP `InstallPackageHelper.installPackagesLI`, which
 * applies the flag for exactly LOCAL_FILE and DOWNLOADED_FILE:
 *
 * ```java
 * if (installRequest.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
 *         || installRequest.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE) {
 *     enableRestrictedSettings(pkgName, pkg.getUid());
 * }
 * ```
 *
 * Corroborated on a Pixel 4a (Android 13) across 25 installed third-party
 * packages: every package with `packageSource` 3 or 4 read `deny` from
 * `cmd appops` (= MODE_ERRORED, what `enableRestrictedSettings` sets), and
 * every `packageSource=0` package read `allow` or had no entry.
 */
class RestrictedSettingsManagerTest {

    /** A file manager installing a downloaded APK — the reported case. */
    @Test
    fun `LOCAL_FILE is restricted`() {
        assertTrue(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE,
            ),
        )
    }

    /** A browser installing straight from its own download. */
    @Test
    fun `DOWNLOADED_FILE is restricted`() {
        assertTrue(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE,
            ),
        )
    }

    /**
     * `adb install` / `pm install` leave the source UNSPECIFIED and are never
     * flagged. This is why the bug is invisible in development, and why this
     * case must not read as restricted — every internal test build takes it.
     */
    @Test
    fun `UNSPECIFIED is not restricted`() {
        assertFalse(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED,
            ),
        )
    }

    /** Play / any app store. */
    @Test
    fun `STORE is not restricted`() {
        assertFalse(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_STORE,
            ),
        )
    }

    /** The installer declared something else explicitly. */
    @Test
    fun `OTHER is not restricted`() {
        assertFalse(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_OTHER,
            ),
        )
    }

    /** An unknown / future source must not be guessed into a scary banner. */
    @Test
    fun `unknown sources are not restricted`() {
        assertFalse(RestrictedSettingsManager.isRestrictedSource(Int.MAX_VALUE))
        assertFalse(RestrictedSettingsManager.isRestrictedSource(-1))
    }
}
