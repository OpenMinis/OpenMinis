package com.openminis.app.config.confirm

import com.openminis.app.config.ConfigRisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-config-confirm-timeout] Verifies the gate's background-notification
 * bookkeeping (widened-timeout companion work): once-per-id delivery, cleared on
 * resolve, queue-advance nudging the next change, and the "app went to
 * background" (notifyPending) path. Mirrors the intent of iOS
 * ConfigConfirmationGate.notifyIfBackgrounded.
 *
 * The gate is a global object; each test drains any leftover pending change and
 * clears the injected hooks so runs stay independent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigConfirmationGateNotifyTest {

    private val dispatcher = StandardTestDispatcher()
    private val notified = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()

    // Simulated foreground state; the injected notifier honours it like the
    // real ConfigConfirmNotifier does.
    private var appForeground = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        notified.clear()
        cancelled.clear()
        appForeground = false
        ConfigConfirmationGate.backgroundNotifier = { change ->
            if (appForeground) {
                false
            } else {
                notified.add(change.id)
                true
            }
        }
        ConfigConfirmationGate.cancelNotification = { id -> cancelled.add(id) }
    }

    @After
    fun tearDown() {
        // Drain anything still pending so the singleton doesn't leak into the
        // next test, then unhook.
        ConfigConfirmationGate.userReject()
        ConfigConfirmationGate.backgroundNotifier = null
        ConfigConfirmationGate.cancelNotification = null
        Dispatchers.resetMain()
    }

    private fun change(caption: String? = "cap") = PendingConfigChange(
        items = listOf(
            PendingConfigChangeItem(
                displayName = "Field",
                path = "a.b",
                oldDisplay = "1",
                newDisplay = "2",
                verb = "set",
                risk = ConfigRisk.NORMAL,
            ),
        ),
        caption = caption,
    )

    @Test
    fun `cancelling the caller clears the pending dialog`() = runTest(dispatcher) {
        val c = change()
        val job = launch { ConfigConfirmationGate.requestConfirmation(c) }
        runCurrent()
        assertEquals(c.id, ConfigConfirmationGate.pending.value?.id)

        // CFG-1: the offload watchdog cancels at 20 s; the gate must not leave
        // a dead request in front of the queue for the full 120 s.
        job.cancel()
        runCurrent()
        assertNull(ConfigConfirmationGate.pending.value)
        assertTrue("cancel notification cleared", c.id in cancelled)
    }

    @Test
    fun `backgrounded front-of-queue change is notified once`() = runTest(dispatcher) {
        val c = change()
        launch { ConfigConfirmationGate.requestConfirmation(c) }
        runCurrent()

        assertEquals(listOf(c.id), notified)

        // A redundant nudge (e.g. later didEnterBackground) must not double-post.
        ConfigConfirmationGate.notifyPending()
        runCurrent()
        assertEquals("still exactly one notification", listOf(c.id), notified)
    }

    @Test
    fun `foreground change is not notified`() = runTest(dispatcher) {
        appForeground = true
        val c = change()
        launch { ConfigConfirmationGate.requestConfirmation(c) }
        runCurrent()
        assertTrue("no notification while foreground", notified.isEmpty())
    }

    @Test
    fun `resolving clears the notification and lets a later change notify again`() =
        runTest(dispatcher) {
            val c = change()
            launch { ConfigConfirmationGate.requestConfirmation(c) }
            runCurrent()
            assertEquals(listOf(c.id), notified)

            ConfigConfirmationGate.userReject()
            runCurrent()
            assertEquals("resolve cancels the pending-approval notice", listOf(c.id), cancelled)

            // A brand-new change (distinct id) notifies afresh.
            val c2 = change()
            launch { ConfigConfirmationGate.requestConfirmation(c2) }
            runCurrent()
            assertEquals(listOf(c.id, c2.id), notified)
        }

    @Test
    fun `queue advance notifies the next backgrounded change`() = runTest(dispatcher) {
        val first = change("first")
        val second = change("second")
        launch { ConfigConfirmationGate.requestConfirmation(first) }
        runCurrent()
        // second stacks behind first — not front-of-queue yet, so not notified.
        launch { ConfigConfirmationGate.requestConfirmation(second) }
        runCurrent()
        assertEquals("only the front change is notified", listOf(first.id), notified)

        // Resolve first → second becomes front-of-queue → it gets nudged.
        ConfigConfirmationGate.userReject()
        runCurrent()
        assertEquals(listOf(first.id, second.id), notified)
    }

    @Test
    fun `foreground app going to background nudges the pending change`() = runTest(dispatcher) {
        appForeground = true
        val c = change()
        launch { ConfigConfirmationGate.requestConfirmation(c) }
        runCurrent()
        assertTrue(notified.isEmpty()) // shown in foreground, no notice yet

        // User switches away → MinisApp calls notifyPending().
        appForeground = false
        ConfigConfirmationGate.notifyPending()
        runCurrent()
        assertEquals(listOf(c.id), notified)
    }
}
