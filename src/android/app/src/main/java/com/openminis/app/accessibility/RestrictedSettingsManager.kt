package com.openminis.app.accessibility

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager

/**
 * [T-android-restricted-settings] Detects Android 13+ "restricted settings"
 * and offers to clear it.
 *
 * ## Why this exists
 *
 * A user on Redmi Note 12 Pro+ 5G (HyperOS 2.0.10 / Android 14) updated Minis
 * from the website and could no longer enable the accessibility service
 * backing `android-a11y-cli`: tapping Minis under Settings → Accessibility
 * pops "Restricted setting — for your security, this setting is currently
 * unavailable" and the toggle never moves. Older builds enabled fine.
 *
 * ## Root cause (an OS policy, not a Minis regression)
 *
 * AOSP `InstallPackageHelper.installPackagesLI` ends with:
 *
 * ```java
 * // Apply restricted settings on potentially dangerous packages.
 * if (installRequest.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
 *         || installRequest.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE) {
 *     enableRestrictedSettings(pkgName, pkg.getUid());
 * }
 * ```
 *
 * and `enableRestrictedSettings` sets `OP_ACCESS_RESTRICTED_SETTINGS` to
 * `MODE_ERRORED` for every user. While that op is errored the Settings app
 * refuses to arm the accessibility toggle (same gate covers notification
 * listeners and device admin).
 *
 * The trigger is purely the `packageSource` the INSTALLING app declared via
 * `SessionParams.setPackageSource`. The framework does not infer or police it
 * — see `PackageInstallerService.createSession`, which passes
 * `params.packageSource` into `InstallSource.create` unchecked. So:
 *
 * - `adb install` / `pm install` leaves it `PACKAGE_SOURCE_UNSPECIFIED` (0) →
 *   never restricted. This is why the problem is invisible to us in
 *   development and why every internal test build enables fine.
 * - A file manager or browser installing a downloaded APK declares 3/4 →
 *   restricted.
 * - A store declares `PACKAGE_SOURCE_STORE` (2) → not restricted.
 *
 * Confirmed on a Pixel 4a (Android 13) across 25 installed third-party
 * packages: every `packageSource=3`/`4` package reads `deny` (= MODE_ERRORED)
 * for this op, and every `packageSource=0` package reads `allow`/default. The
 * correlation is exact.
 *
 * ### Why the user's "old version worked" and why SAI did not help
 *
 * Nothing changed in Minis. What changed is how that particular copy was
 * installed: an APK opened from a browser/file manager download declares
 * `DOWNLOADED_FILE`, so the flag is applied at install time and then persists
 * across in-place updates. SAI is session-based, but session-based is NOT the
 * exemption — SAI still declares a local-file source, so reinstalling through
 * it re-applies the very same flag. That is exactly what the user observed.
 *
 * ### Why we cannot fix this from inside the app
 *
 * `ACCESS_RESTRICTED_SETTINGS` is set on OUR uid by the installer, and
 * clearing it needs `android.permission.MANAGE_APP_OPS_MODES` (signature).
 * An app cannot exempt itself — by design, since the whole point is to stop a
 * sideloaded app from talking the user into granting it screen-reading power.
 * The op is additionally `restrictRead`, so we cannot even *observe* it (see
 * [isRestricted]). So the honest remedy is detection via the install source
 * plus accurate guidance, with an optional one-tap fix when the user already
 * has Shizuku (which runs as shell and does hold the permission).
 *
 * ## What this does NOT do
 *
 * It does not change how Minis is packaged or installed, and deliberately so.
 * We could make the in-app updater install through a `PackageInstaller`
 * session declaring `PACKAGE_SOURCE_STORE` — the framework does not verify
 * that claim — and self-updates would stop being flagged. That is a
 * misrepresentation of provenance made solely to dodge a user-protection
 * prompt, it would not help the reported user (whose copy came from a browser
 * download, not from our updater), and it cannot fix an already-flagged
 * install anyway. Guidance is the correct fix; the user stays the one who
 * decides.
 */
object RestrictedSettingsManager {

    private const val TAG = "RestrictedSettings"

    /**
     * Set once [clearWithShizuku] has verifiably lifted the flag. Deliberately
     * in-memory only: it is a display latch, not persisted state, and a fresh
     * process re-derives the truth from the install source (plus, if the user
     * has by then enabled the service, the guidance is gated off anyway).
     */
    @Volatile
    private var cleared = false

    /**
     * True when the OS has flagged this install and will therefore block the
     * accessibility toggle.
     *
     * ### Why we do NOT read the appop directly
     *
     * The obvious probe — `unsafeCheckOpNoThrow("android:access_restricted_settings", …)`
     * on our own uid — does not work, and fails in a way that is easy to miss:
     * it throws SecurityException rather than returning a mode. The op is
     * declared `.setRestrictRead(true)` in AppOpsManager's op table, and
     * `AppOpsService.verifyIncomingOp` turns that into
     * `enforcePermission(MANAGE_APPOPS)` for EVERY caller — reading your own
     * op is not exempt. Verified on a Pixel 4a (Android 13): with the op set
     * to `deny` via `cmd appops`, the in-app probe logged
     * "SecurityException" once per poll and the guidance never appeared.
     *
     * ### What we read instead
     *
     * The same input the framework itself keys the decision on: the
     * `packageSource` our installer declared. `InstallPackageHelper` applies
     * the flag for exactly `PACKAGE_SOURCE_LOCAL_FILE` (3) and
     * `PACKAGE_SOURCE_DOWNLOADED_FILE` (4), and
     * `InstallSourceInfo.getPackageSource()` is public API readable for our
     * own package with no permission. So this is not a heuristic — it is the
     * same condition, read from the other side.
     *
     * `getPackageSource()` was added in Android 14 (U). On Android 13 the flag
     * exists but the value is unreadable, so we return false there and leave
     * today's behaviour: the reported device is Android 14, and guessing on 13
     * risks showing the banner to users whose install is fine.
     *
     * Returns false on anything unexpected. A false negative just leaves the
     * pre-existing behaviour; a false positive would send every user chasing a
     * setting that isn't there.
     */
    fun isRestricted(context: Context): Boolean {
        // Once we have actually cleared the op this session, stop reporting
        // restricted. The install source (what isRestricted reads) does not
        // change when the flag is lifted, and the op itself is unreadable to
        // us, so without this latch the guidance would never go away.
        if (cleared) return false
        // getPackageSource() is UPSIDE_DOWN_CAKE+ (Android 14).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val source = context.packageManager
                .getInstallSourceInfo(context.packageName)
                .packageSource
            val restricted = isRestrictedSource(source)
            if (restricted) {
                AppLogger.info(TAG, "install flagged restricted (packageSource=$source)")
            }
            restricted
        } catch (t: Throwable) {
            AppLogger.info(TAG, "install-source probe unavailable: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * Classify a `PackageInstaller.PACKAGE_SOURCE_*` value. Split out so the
     * decision is testable on the JVM (no Robolectric in this module).
     *
     * Mirrors `InstallPackageHelper`'s condition exactly — only LOCAL_FILE and
     * DOWNLOADED_FILE trigger the flag. UNSPECIFIED (adb/pm), STORE and OTHER
     * must NOT, or we would put a "your install is blocked" banner in front of
     * users whose install is perfectly fine.
     */
    internal fun isRestrictedSource(packageSource: Int): Boolean =
        packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE

    /**
     * Clear the flag via Shizuku, which runs as `shell` and so holds
     * `MANAGE_APP_OPS_MODES`. This is the same privileged channel
     * [AccessibilityRecoveryManager.repairWithShizuku] uses.
     *
     * Equivalent to the command the reporting user ran by hand:
     * `appops set com.openminis.app ACCESS_RESTRICTED_SETTINGS allow`.
     *
     * @return true once the op reads back as allowed.
     */
    suspend fun clearWithShizuku(context: Context): Boolean {
        if (!ShizukuManager.isReady()) {
            AppLogger.info(TAG, "clear skipped: Shizuku not ready")
            return false
        }
        val set = ShizukuManager.runProcess(
            arrayOf(
                "appops", "set", context.packageName,
                "ACCESS_RESTRICTED_SETTINGS", "allow",
            ),
        )
        if (set.exitCode != 0) {
            AppLogger.warning(TAG, "appops set failed: exit=${set.exitCode} ${set.combined}")
            return false
        }
        // Verify rather than trust the exit code. Note we must re-read through
        // Shizuku too: [isRestricted] reads the INSTALL SOURCE, which never
        // changes, and the appop itself is read-restricted to MANAGE_APPOPS
        // holders — so the in-process probe cannot observe this write at all.
        val get = ShizukuManager.runProcess(
            arrayOf("appops", "get", context.packageName, "ACCESS_RESTRICTED_SETTINGS"),
        )
        // `cmd appops get` prints e.g. "ACCESS_RESTRICTED_SETTINGS: allow".
        // "deny" is how the shell renders MODE_ERRORED.
        val ok = get.exitCode == 0 && get.combined.contains("allow")
        if (ok) cleared = true
        AppLogger.info(TAG, "cleared restricted settings via Shizuku; ok=$ok (${get.combined.trim()})")
        return ok
    }
}
