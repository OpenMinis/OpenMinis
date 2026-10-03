package com.openminis.app.data

import java.io.File
import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-models-refresh-window] Model lists refreshed at most once per
 * CALENDAR DAY, so a model enabled mid-day stayed invisible until the next one.
 *
 * Port of iOS fe625d5fd, reported there with a trace: GitHub turned on
 * gpt-6-astra account-wide during the morning, the app had already refreshed
 * at 09:55, and it made zero further `/models` calls until a manual refresh at
 * 22:20 — which returned it immediately. Nothing was whitelisted; the app
 * simply would not ask again that day.
 *
 * The network call needs a live provider, so what is verifiable here is the
 * gate's arithmetic (which is what was wrong) plus the wiring that decides
 * when it is consulted.
 */
class ModelsRefreshWindowTest {

    private val windowMs = 6 * 60 * 60 * 1000L

    /** The new gate, mirrored from ProviderRepository. */
    private fun shouldSkip(lastMs: Long, nowMs: Long): Boolean =
        lastMs > 0L && (nowMs - lastMs) < windowMs

    /** The OLD gate, kept to show what changed. */
    private fun sameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = aMs
        val aYear = cal.get(Calendar.YEAR)
        val aDay = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = bMs
        return aYear == cal.get(Calendar.YEAR) && aDay == cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 19, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- The reported scenario --------------------------------------------

    @Test
    fun `the reported gap - refreshed at 0955, asked again at 2220`() {
        val last = at(9, 55)
        val later = at(22, 20)

        // Old behaviour: same calendar day -> skipped, which is the bug.
        assertTrue("the old gate skipped this", sameCalendarDay(last, later))
        // New behaviour: 12h25m elapsed, well past the window -> refresh.
        assertFalse("the rolling window must refresh here", shouldSkip(last, later))
    }

    @Test
    fun `a refresh within the window is still skipped`() {
        // The window has to actually bound the cost, not just always fire.
        val last = at(9, 55)
        assertTrue(shouldSkip(last, at(10, 30)))   // 35m
        assertTrue(shouldSkip(last, at(13, 0)))    // ~3h
        assertTrue(shouldSkip(last, at(15, 54)))   // 5h59m — just inside
    }

    @Test
    fun `the boundary fires exactly at six hours`() {
        val last = at(9, 55)
        assertTrue("one ms before the window closes", shouldSkip(last, last + windowMs - 1))
        assertFalse("exactly at the window", shouldSkip(last, last + windowMs))
        assertFalse("after the window", shouldSkip(last, last + windowMs + 1))
    }

    @Test
    fun `a first run with no recorded refresh always fires`() {
        assertFalse("lastMs = 0 means never refreshed", shouldSkip(0L, at(9, 55)))
    }

    @Test
    fun `crossing midnight inside the window does NOT force a refresh`() {
        // The mirror image of the bug: the old gate fired at 00:00 no matter
        // how recently it had run. A refresh at 23:50 followed by a launch at
        // 00:10 is twenty minutes apart and must stay skipped.
        val last = at(23, 50)
        val justAfterMidnight = last + 20 * 60 * 1000L
        assertFalse("the old gate refreshed again immediately", sameCalendarDay(last, justAfterMidnight))
        assertTrue("the window correctly skips", shouldSkip(last, justAfterMidnight))
    }

    @Test
    fun `worst-case staleness drops from a day to the window`() {
        val last = at(0, 5)
        // Old gate: still skipping at 23:59 — almost 24h stale.
        assertTrue(sameCalendarDay(last, at(23, 59)))
        // New gate: refreshes once past six hours.
        assertFalse(shouldSkip(last, at(6, 6)))
    }

    // ---- Source facts ------------------------------------------------------

    private fun src(path: String) = File(path).readText()

    private val repoSrc by lazy {
        src("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt")
    }
    private val appSrc by lazy { src("src/main/java/com/openminis/app/MinisApp.kt") }

    @Test
    fun `the calendar-day gate is gone`() {
        assertFalse(
            "isSameCalendarDay must not survive as a live gate",
            repoSrc.contains("private fun isSameCalendarDay"),
        )
        assertTrue(repoSrc.contains("MODELS_REFRESH_WINDOW_MS"))
    }

    @Test
    fun `the window is six hours and distinct from the per-instance cache TTL`() {
        assertTrue(
            "window must be 6h",
            repoSrc.contains("MODELS_REFRESH_WINDOW_MS = 6 * 60 * 60 * 1000L"),
        )
        assertTrue(
            "the SWR per-instance TTL is a different knob and stays at 24h",
            repoSrc.contains("MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L"),
        )
    }

    @Test
    fun `a foreground return re-checks the model lists`() {
        // Launch alone cannot see a model enabled while the app sits open —
        // that is precisely the reported case.
        val resumed = appSrc.substringAfter("override fun onActivityResumed")
            .substringBefore("override fun onActivityPaused")
        assertTrue(
            "onActivityResumed must call refreshAllModelsIfNeeded",
            resumed.contains("refreshAllModelsIfNeeded"),
        )
    }

    // ---- [M01] Boundary + first-launch + resume, stated exactly -----------
    //
    // The cases above cover the reported gap and the window's arithmetic. What
    // follows pins the three specific edges e7818575e introduced, each of which
    // is a plausible "simplification" away from being wrong again.

    @Test
    fun `five hours fifty-nine skips and six hours one refreshes`() {
        // The two sides of the boundary named in the spec, as elapsed times
        // rather than wall-clock hours — so the assertion holds regardless of
        // which day or timezone the suite runs in.
        val last = at(9, 55)
        val minute = 60 * 1000L
        assertTrue("5h59m must still skip", shouldSkip(last, last + 5 * 60 * minute + 59 * minute))
        assertFalse("6h01m must refresh", shouldSkip(last, last + 6 * 60 * minute + 1 * minute))
    }

    @Test
    fun `a first launch with no stored date refreshes rather than waiting six hours`() {
        // `lastMs > 0L` is the "never refreshed" guard. Dropping it would make a
        // fresh install compare against epoch 0, which is billions of ms ago and
        // happens to work — but the same expression with a `>= 0` or a
        // `lastMs.coerceAtLeast(now)` style rewrite would make a new user wait
        // six hours for their first model list. Pinned at the boundary value.
        assertFalse("no stored date", shouldSkip(0L, at(9, 55)))
        assertFalse("epoch zero is not 'just refreshed'", shouldSkip(0L, 0L + windowMs - 1))
    }

    @Test
    fun `a clock moved backwards does not wedge the gate shut`() {
        // A stored timestamp in the FUTURE (NTP correction, user changing the
        // device clock) makes `now - last` negative, which is `< windowMs` and
        // therefore skips. That is the safe direction — it delays a refresh, it
        // does not spam — but it must not persist: once real time passes the
        // stored stamp by six hours the gate opens again.
        val future = at(20, 0)
        assertTrue("a future stamp skips, by design", shouldSkip(future, at(9, 55)))
        assertFalse("and recovers once the window truly elapses", shouldSkip(future, future + windowMs))
    }

    @Test
    fun `the gate reads the stored timestamp inside the coroutine, not before it`() {
        // [T-android-startup-prefs-stall] The early-return-before-launch shape
        // was the cold-start hang: `prefs.getLong` blocks until the prefs XML is
        // parsed, and this is called from MinisApp.onCreate on the main thread
        // (HangDetector measured ~9.6s on a Pixel 4a). Re-hoisting the read for
        // "clarity" reintroduces a main-thread stall that no functional test
        // would catch, so the ordering is pinned here.
        val fn = repoSrc.substringAfter("fun refreshAllModelsIfNeeded(")
            .substringBefore("/** Resolve the models.dev lookup base URL")
        val launchAt = fn.indexOf("scope.launch {")
        val readAt = fn.indexOf("prefs.getLong(key")
        assertTrue("both must be present", launchAt >= 0 && readAt >= 0)
        assertTrue(
            "prefs.getLong must happen INSIDE scope.launch",
            launchAt < readAt,
        )
        assertTrue(
            "the window comparison belongs in the coroutine too",
            fn.indexOf("MODELS_REFRESH_WINDOW_MS") > launchAt,
        )
    }

    @Test
    fun `the stamp is written before the per-instance fan-out`() {
        // Ordering matters for concurrency: two resume events in quick
        // succession must not both fire the fan-out. Writing the timestamp
        // BEFORE launching the per-instance refreshes makes the second call skip
        // even while the first is still in flight.
        val fn = repoSrc.substringAfter("fun refreshAllModelsIfNeeded(")
            .substringBefore("/** Resolve the models.dev lookup base URL")
        val writeAt = fn.indexOf("putLong(key, now)")
        val fanOutAt = fn.indexOf("autoRefreshModels(instance)")
        assertTrue("both must be present", writeAt >= 0 && fanOutAt >= 0)
        assertTrue("the stamp must be written first", writeAt < fanOutAt)
    }

    @Test
    fun `the launch path still calls it too`() {
        // The foreground hook adds to the launch trigger, it does not replace
        // it: a cold start has no resume event to piggyback on.
        assertTrue(appSrc.contains("providerRepository.refreshAllModelsIfNeeded("))
        val count = Regex("refreshAllModelsIfNeeded\\(").findAll(appSrc).count()
        assertTrue("expected both the launch and resume call sites, found $count", count >= 2)
    }
}
