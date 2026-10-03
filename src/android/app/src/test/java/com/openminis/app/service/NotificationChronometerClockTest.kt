package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-notification-chronometer] The elapsedRealtime → wall-clock
 * conversion behind `setWhen()`.
 *
 * Why this narrow thing gets a test: it is the only arithmetic in the change,
 * and its failure mode is silent and enormous. `SystemClock.elapsedRealtime()`
 * counts from boot; `Notification.setWhen()` is read as
 * `System.currentTimeMillis()`. Handing the former straight to the latter puts
 * the timer's origin at the epoch plus the device's uptime — the chip shows
 * days where it should show seconds — and nothing throws or logs.
 */
class NotificationChronometerClockTest {

    /** A device up for 5 days; the run started 90s ago. */
    private val upFiveDaysMs = 5L * 24 * 60 * 60 * 1000
    private val nowWall = 1_789_000_000_000L

    @Test
    fun `a run that started 90s ago maps to 90s before now`() {
        val nowElapsed = upFiveDaysMs
        val startElapsed = nowElapsed - 90_000L

        val wall = AgentForegroundService.elapsedRealtimeToWallClock(
            startElapsed, nowElapsed, nowWall,
        )

        assertEquals(nowWall - 90_000L, wall)
    }

    /**
     * The regression guard proper: the naive implementation (passing
     * elapsedRealtime through unchanged) would place the origin ~5 days after
     * the epoch instead of near today, so the rendered timer would be off by
     * the device's entire uptime.
     */
    @Test
    fun `the result is not the raw elapsedRealtime value`() {
        val nowElapsed = upFiveDaysMs
        val startElapsed = nowElapsed - 90_000L

        val wall = AgentForegroundService.elapsedRealtimeToWallClock(
            startElapsed, nowElapsed, nowWall,
        )

        assertTrue(
            "must not pass elapsedRealtime through unconverted",
            wall != startElapsed,
        )
        // And the error the naive version would make is days, not milliseconds.
        val naiveErrorMs = wall - startElapsed
        assertTrue("naive error should be enormous", naiveErrorMs > 365L * 24 * 60 * 60 * 1000)
    }

    /** "Now" maps to now — the zero-elapsed edge. */
    @Test
    fun `an instant equal to now maps to now`() {
        val nowElapsed = upFiveDaysMs
        assertEquals(
            nowWall,
            AgentForegroundService.elapsedRealtimeToWallClock(nowElapsed, nowElapsed, nowWall),
        )
    }

    /**
     * Both conversions in one rebuild share the same pair of "now" values, so
     * a finish stamp that is 30s older than the start anchor keeps exactly that
     * 30s gap — the completed chip must read the run's real duration.
     */
    @Test
    fun `two instants keep their spacing`() {
        val nowElapsed = upFiveDaysMs
        val startElapsed = nowElapsed - 120_000L
        val finishElapsed = nowElapsed - 90_000L

        val startWall = AgentForegroundService.elapsedRealtimeToWallClock(
            startElapsed, nowElapsed, nowWall,
        )
        val finishWall = AgentForegroundService.elapsedRealtimeToWallClock(
            finishElapsed, nowElapsed, nowWall,
        )

        assertEquals(30_000L, finishWall - startWall)
    }

    /** A freshly booted device (uptime ~0) must also land on now. */
    @Test
    fun `works on a freshly booted device`() {
        assertEquals(
            nowWall - 5_000L,
            AgentForegroundService.elapsedRealtimeToWallClock(1_000L, 6_000L, nowWall),
        )
    }
}
