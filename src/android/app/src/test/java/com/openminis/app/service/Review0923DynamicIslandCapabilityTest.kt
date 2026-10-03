package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — the Live Updates ("dynamic island") capability can flip
 * at runtime and must be re-evaluated.
 *
 * Guards fde5a18be (T-android-island-capability-cache) against the contract
 * documented on DynamicIslandSupport itself and relied on by
 * BackgroundSettingsScreen (re-probe on ON_RESUME) and
 * AgentForegroundService.applyOverlayState ("toggling either the app switch
 * or the system Live-Updates grant hides/reveals the overlay live").
 *
 * [BUG] fde5a18be memoized `canPostPromotedNotifications()` for the life of
 * the process, reasoning that it "cannot change while the process lives". It
 * can: it reflects the per-app Live Updates grant the user toggles in system
 * settings (the class KDoc says so). Consequences:
 *   - user revokes Live Updates mid-process: cache stays true, so
 *     applyOverlayState keeps suppressing the floating capsule while the
 *     system refuses the promoted chip — the user sees neither surface;
 *   - user grants it later: cache stays false until the process dies, and the
 *     settings screen's ON_RESUME re-probe reads the stale value.
 * Minimal fix: keep the ticker cheap but make the cache expire — e.g. a
 * short TTL (a few seconds), or an `invalidateCapabilityCache()` called from
 * the ON_RESUME re-probe and from the service's app-foreground transition.
 */
class Review0923DynamicIslandCapabilityTest {

    @Test
    fun `the capability cache has a way to be invalidated`() {
        val src = ProductionSources.read("service/DynamicIslandSupport.kt")
        if (!src.contains("capableCache")) return // no cache at all: contract holds
        val invalidated = Regex("capableCache\\s*=\\s*null").containsMatchIn(src)
        val expires = Regex("(?i)\\bttl|elapsedRealtime|currentTimeMillis|nanoTime|expir").containsMatchIn(
            src.substringAfter("capableCache"),
        )
        assertTrue(
            "DynamicIslandSupport caches canPostPromotedNotifications() for the process " +
                "lifetime, but the user can toggle Live Updates at runtime — a revoked grant " +
                "leaves the overlay suppressed with no island shown",
            invalidated || expires,
        )
    }

    @Test
    fun `the class contract and the settings re-probe still assume a runtime flip`() {
        // If someone decides the capability really is process-static, both of
        // these must be revisited together with the cache — not left
        // contradicting it.
        val doc = ProductionSources.read("service/DynamicIslandSupport.kt")
        val settings = ProductionSources.read("ui/settings/BackgroundSettingsScreen.kt")
        assertTrue(doc.contains("can flip at runtime"))
        assertTrue(settings.contains("Lifecycle.Event.ON_RESUME") && settings.contains("isDynamicIslandCapable"))
    }
}
