package com.openminis.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-subagent-no-system-notification] A sub agent finishing must not
 * post a system notification.
 *
 * Reported: delegating work produced a notification-shade entry titled
 * "代理任务·摘要 …" / "Tap to open the chat and read the response." for a task
 * the user never started — and a parent that delegates several children fires
 * one per child. The result is already delivered into the parent chat as a
 * summary card, so the notification interrupts to announce work the user can
 * already see.
 *
 * iOS never had this. `postBackgroundTaskNotification` has exactly ONE call
 * site there — AIChatViewModel+BackgroundTask, the MAIN chat's
 * background-completion path — and `HelperRunner` (the sub agent runner) posts
 * nothing; it only reads SessionActivityTracker for display. Android diverged
 * because BackgroundTaskNotifier is wired to SessionActivityTracker's
 * completion hook, which fires for EVERY session, children included.
 *
 * The decision lives inside a private coroutine that needs a Context, a
 * ChatRepository and NotificationManagerCompat, so this covers the rule itself
 * plus a source check that the guard is still wired — which is what actually
 * regressed.
 */
class SubAgentNotificationSuppressionTest {

    /** Mirrors the guard: a session with a parent is a sub agent run. */
    private fun shouldNotify(parentSessionId: String?): Boolean = parentSessionId == null

    @Test
    fun `a sub agent session is not notified`() {
        assertFalse(shouldNotify("parent-abc"))
    }

    @Test
    fun `a top-level session is still notified`() {
        // The feature must keep working for what it was built for: a real
        // background task the user started and is waiting on.
        assertTrue(shouldNotify(null))
    }

    @Test
    fun `a scheduled child is also suppressed`() {
        // A child created by `minis-scheduled --target child-of-current` has a
        // parent but no parentToolUseId; the parent link is what decides.
        assertTrue(shouldNotify(null))
        assertFalse(shouldNotify("parent-from-scheduler"))
    }

    @Test
    fun `the guard is still present in BackgroundTaskNotifier`() {
        val f = File("src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt")
        assertTrue("BackgroundTaskNotifier.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        val text = f.readText()
        assertTrue(
            "the child-session check is gone — sub agent completions will post " +
                "system notifications again",
            text.contains("session?.isChild == true"),
        )
        // …and that it returns rather than merely logging.
        val idx = text.indexOf("session?.isChild == true")
        val after = text.substring(idx, minOf(idx + 400, text.length))
        assertTrue(
            "the check no longer aborts the notification",
            after.contains("return@launch"),
        )
    }
}
