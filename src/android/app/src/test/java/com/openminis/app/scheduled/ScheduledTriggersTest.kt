package com.openminis.app.scheduled

import com.openminis.app.sandbox.offload.OffloadArgs
import com.openminis.app.sandbox.offload.ScheduledTaskOffloadHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-android-scheduled-triggers] minis-scheduled's iOS triggers on Android
 * (8b315deba / 993b0a5e0 / 3b69e3d00): --after, --interval/--count,
 * --trigger on-completion --of, --thinking — persisted and armed through
 * AlarmManager, without disturbing the in-app calendar tasks.
 */
class ScheduledTriggersTest {

    private val t0 = 1_800_000_000_000L

    private fun task(
        kind: ScheduledTriggerKind = ScheduledTriggerKind.CALENDAR,
        delaySec: Long? = null,
        intervalSec: Long? = null,
        maxFires: Int? = null,
        of: String? = null,
        anchor: Long? = t0,
        triggered: Int? = null,
        enabled: Boolean = true,
        repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
    ) = ScheduledTask(
        id = "t1", label = "watch", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = repeat, prompt = "Check: {{result}}", enabled = enabled, createdAt = t0,
        triggerKind = kind, delaySec = delaySec, intervalSec = intervalSec, maxFires = maxFires,
        onCompletionOf = of, anchorMs = anchor, triggeredCount = triggered,
    )

    // ── Backward compatibility ────────────────────────────────────────────

    @Test
    fun `a task stored before triggers existed is still a calendar task`() {
        val old = org.json.JSONObject(
            """{"id":"a","label":"L","hour":8,"minute":30,"repeatMode":"DAILY","prompt":"p","enabled":true,"createdAt":$t0}""",
        )
        val t = ScheduledTask.fromJson(old)
        assertEquals(ScheduledTriggerKind.CALENDAR, t.triggerKind)
        assertTrue(t.isCalendar)
        assertNull(t.delaySec); assertNull(t.intervalSec); assertNull(t.anchorMs)
        // No trigger keys are written for a calendar task, so an older build
        // reading this row sees exactly what it always did.
        assertFalse(t.toJson().has("trigger"))
    }

    @Test
    fun `every trigger field survives a round trip`() {
        val t = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 6, triggered = 2)
        assertEquals(t, ScheduledTask.fromJson(t.toJson()))
        val oc = task(ScheduledTriggerKind.ON_COMPLETION, of = "upstream-1", anchor = null)
        assertEquals(oc, ScheduledTask.fromJson(oc.toJson()))
    }

    // ── When they fire ────────────────────────────────────────────────────

    @Test
    fun `after fires once, delay after the anchor, and never again`() {
        val t = task(ScheduledTriggerKind.AFTER, delaySec = 1800)
        assertEquals(t0 + 1_800_000, t.nextTriggerMs(now = t0))
        assertEquals("overdue fires late, once", t0 + 5_000_000, t.nextTriggerMs(now = t0 + 5_000_000))
        assertNull(t.copy(triggeredCount = 1).nextTriggerMs(now = t0))
        assertTrue(t.isOneShot)
    }

    @Test
    fun `an interval counts from its anchor and stops at its count`() {
        val t = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 3, triggered = 1)
        assertEquals(t0 + 600_000, t.nextTriggerMs(now = t0))
        assertEquals(2, t.remainingFires)
        assertNull("exhausted", t.copy(triggeredCount = 3).nextTriggerMs(now = t0))
        assertNull("unbounded loop has no remaining count", task(ScheduledTriggerKind.INTERVAL, intervalSec = 600).remainingFires)
        assertFalse(t.isOneShot)
    }

    @Test
    fun `on-completion has no clock time`() {
        assertNull(task(ScheduledTriggerKind.ON_COMPLETION, of = "x").nextTriggerMs(now = t0))
    }

    @Test
    fun `an alarm fire counts, re-anchors and disables at the last of the count`() {
        val iv = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 2, triggered = 0)
        val (first, armFirst) = ScheduledTaskManager.afterAlarmFire(iv, now = t0 + 600_000)!!
        assertEquals(1, first.triggeredCount)
        assertEquals(t0 + 600_000, first.anchorMs)
        assertTrue("next fire armed", armFirst)
        assertEquals(t0 + 1_200_000, first.nextTriggerMs(now = t0 + 600_000))

        val (last, armLast) = ScheduledTaskManager.afterAlarmFire(first, now = t0 + 1_200_000)!!
        assertEquals(2, last.triggeredCount)
        assertFalse("the last fire disables, it does not re-arm", last.enabled || armLast)

        val (after, armAfter) = ScheduledTaskManager.afterAlarmFire(task(ScheduledTriggerKind.AFTER, delaySec = 60), now = t0)!!
        assertFalse(after.enabled || armAfter)
        assertNull("calendar tasks keep their own path", ScheduledTaskManager.afterAlarmFire(task(), now = t0))
    }

    @Test
    fun `saving from the editor keeps history, counts and the anchor`() {
        val stored = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, triggered = 4)
            .copy(runHistory = listOf(ScheduledRun(t0, "s", "ok", true)), fireCount = 4)
        // What the editor's buildTask produces: none of the bookkeeping.
        val edited = stored.copy(label = "renamed", runHistory = emptyList(), fireCount = null,
            anchorMs = null, triggeredCount = null)
        val merged = ScheduledTaskManager.mergeBookkeeping(edited, stored)
        assertEquals("renamed", merged.label)
        assertEquals(stored.runHistory, merged.runHistory)
        assertEquals(4, merged.fireCount)
        assertEquals(t0, merged.anchorMs)
        assertEquals(4, merged.triggeredCount)
    }

    // ── On-completion ─────────────────────────────────────────────────────

    @Test
    fun `dependents are the enabled on-completion tasks waiting for that id`() {
        val waiting = task(ScheduledTriggerKind.ON_COMPLETION, of = "up")
        val other = waiting.copy(id = "t2", onCompletionOf = "someone-else")
        val disabled = waiting.copy(id = "t3", enabled = false)
        val calendar = task().copy(id = "t4")
        val deps = ScheduledCompletionTriggers.dependents(listOf(waiting, other, disabled, calendar), "up")
        assertEquals(listOf("t1"), deps.map { it.id })
    }

    @Test
    fun `result is substituted and capped`() {
        assertEquals("Check: done", ScheduledCompletionTriggers.substitute("Check: {{result}}", "done"))
        assertEquals("Check: ", ScheduledCompletionTriggers.substitute("Check: {{result}}", null))
        val long = "x".repeat(ScheduledCompletionTriggers.RESULT_CAP_CHARS + 50)
        assertEquals(
            ScheduledCompletionTriggers.RESULT_CAP_CHARS,
            ScheduledCompletionTriggers.substitute("{{result}}", long).length,
        )
    }

    // ── The CLI ───────────────────────────────────────────────────────────

    private fun args(vararg argv: String) =
        OffloadArgs(listOf("create", *argv), booleanFlags = setOf("disabled", "h", "help"))

    private fun trigger(vararg argv: String, resolve: (String) -> String = { it }) =
        ScheduledTaskOffloadHandler.parseTrigger(args(*argv), resolve)

    private fun rejected(vararg argv: String, contains: String) {
        try {
            trigger(*argv)
            fail("expected rejection for ${argv.toList()}")
        } catch (e: IllegalArgumentException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(contains))
        }
    }

    @Test
    fun `the trigger is inferred from the flags, as on iOS`() {
        assertEquals(ScheduledTriggerKind.AFTER, trigger("--after", "30m").kind)
        assertEquals(1800L, trigger("--after", "30m").delaySec)
        val loop = trigger("--interval", "10m", "--count", "6")
        assertEquals(ScheduledTriggerKind.INTERVAL, loop.kind)
        assertEquals(600L, loop.intervalSec); assertEquals(6, loop.maxFires)
        assertNull("no --count = until deleted", trigger("--interval", "10m").maxFires)
        val oc = trigger("--of", "build-watch") { "resolved-id" }
        assertEquals(ScheduledTriggerKind.ON_COMPLETION, oc.kind)
        assertEquals("resolved-id", oc.onCompletionOf)
        assertEquals(ScheduledTriggerKind.ON_COMPLETION, trigger("--trigger", "on-completion", "--of", "x").kind)
    }

    @Test
    fun `--time alone keeps Android's once default, an explicit cron repeats daily`() {
        val plain = trigger("--time", "08:00")
        assertEquals(ScheduledTriggerKind.CALENDAR, plain.kind)
        assertEquals(ScheduledRepeatMode.ONCE, plain.repeat)
        assertEquals(8, plain.hour)
        assertEquals(ScheduledRepeatMode.DAILY, trigger("--trigger", "cron", "--time", "08:00").repeat)
        assertEquals(ScheduledRepeatMode.WEEKDAYS, trigger("--time", "08:00", "--repeat", "weekdays").repeat)
        assertEquals(ScheduledRepeatMode.ONCE, trigger("--trigger", "once", "--time", "08:00").repeat)
    }

    @Test
    fun `bad triggers are refused with a message that says what to do`() {
        rejected(contains = "give a trigger")
        rejected("--after", "soon", contains = "duration like 30m")
        rejected("--interval", "30s", contains = "at least 60s")
        rejected("--interval", "10m", "--count", "0", contains = "positive integer")
        rejected("--trigger", "on-completion", contains = "--of")
        rejected("--trigger", "weekly", contains = "once|loop|cron|on-completion")
        rejected("--trigger", "once", contains = "--after")
        rejected("--after", contains = "needs a value")
    }

    @Test
    fun `--thinking is validated, not coerced`() {
        assertEquals(com.openminis.app.data.model.ThinkingLevel.HIGH, ScheduledTaskOffloadHandler.parseThinking("high"))
        assertEquals(com.openminis.app.data.model.ThinkingLevel.XHIGH, ScheduledTaskOffloadHandler.parseThinking("XHIGH"))
        assertNull(ScheduledTaskOffloadHandler.parseThinking(null))
        try {
            ScheduledTaskOffloadHandler.parseThinking("hgih")
            fail("a typo must not silently pick a level")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("off, low, medium, high"))
        }
    }

    // ── What the task screens show ────────────────────────────────────────

    private val phrases = ScheduledTaskDetailModel.TriggerPhrases(
        once = "Once at %1\$s", daily = "Every day %1\$s", weekdays = "Weekdays %1\$s",
        weekly = "Every %1\$s %2\$s", daySeparator = ", ", dayNames = List(7) { "d$it" },
        from = "from %1\$s", until = "until %1\$s", window = "%1\$s – %2\$s",
    )

    private fun text(t: ScheduledTask, upstream: (String) -> String? = { null }) =
        ScheduledTaskDetailModel.triggerText(t, phrases, { h, m -> "%02d:%02d".format(h, m) }, upstream) { "DAY" }

    @Test
    fun `the detail page names every trigger`() {
        assertEquals("Every 10m · 6 times", text(task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 6)))
        assertEquals("Every 1h30m", text(task(ScheduledTriggerKind.INTERVAL, intervalSec = 5400)))
        assertEquals("When “build-watch” finishes", text(task(ScheduledTriggerKind.ON_COMPLETION, of = "id-1")) { "build-watch" })
        assertTrue(text(task(ScheduledTriggerKind.AFTER, delaySec = 1800)).startsWith("Once at DAY "))
        assertEquals("the calendar wording is unchanged", "Every day 09:00", text(task()))
    }

    @Test
    fun `status and progress read the new triggers correctly`() {
        val marker = ScheduledTaskMarker(taskId = "t1", label = "watch", nextFireAtMs = null, prompt = "p")
        val waitingOc = task(ScheduledTriggerKind.ON_COMPLETION, of = "up")
        assertEquals(ScheduledTaskDetailModel.Status.WAITING, ScheduledTaskDetailModel.status(waitingOc, false, t0))
        assertEquals(1, ScheduledTaskDetailModel.progress(waitingOc, marker, false, t0).remaining)

        val done = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 3, triggered = 3, enabled = false)
        assertEquals(ScheduledTaskDetailModel.Status.COMPLETED, ScheduledTaskDetailModel.status(done, false, t0))

        val running = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 3, triggered = 1)
            .copy(fireCount = 1)
        val p = ScheduledTaskDetailModel.progress(running, marker, false, t0)
        assertEquals(2, p.remaining)
        assertEquals(3, p.total)
    }

    @Test
    fun `the list row summary covers the new triggers`() {
        assertTrue(com.openminis.app.ui.scheduled.formatScheduleSummary(task(ScheduledTriggerKind.AFTER, delaySec = 1800)).startsWith("Once after 30m"))
        assertTrue(
            com.openminis.app.ui.scheduled.formatScheduleSummary(
                task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 6, triggered = 2),
            ).startsWith("Every 10m ×6 (4 left)"),
        )
        assertTrue(com.openminis.app.ui.scheduled.formatScheduleSummary(task(ScheduledTriggerKind.ON_COMPLETION, of = "abcdef123456")).startsWith("After abcdef12"))
    }

    // ── [T-android-scheduled-fire-claim] one delivery, one fire ──────────

    @Test
    fun `a stale second delivery of an interval fire is not due`() {
        val iv = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 6, triggered = 0)
        val fireAt = t0 + 600_000
        assertTrue("the real fire", ScheduledTaskManager.relativeAlarmDue(iv, fireAt))
        val (counted, _) = ScheduledTaskManager.afterAlarmFire(iv, fireAt)!!
        // rescheduleAll re-armed it for "now" from the pre-count row: that
        // delivery arrives a moment later and must find nothing to run.
        assertFalse(ScheduledTaskManager.relativeAlarmDue(counted, fireAt + 50))
        assertTrue("the next slot is still due at its time", ScheduledTaskManager.relativeAlarmDue(counted, fireAt + 600_000))
    }

    @Test
    fun `overdue after a reboot is due, exhausted and fired are not`() {
        val iv = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 2, triggered = 0)
        assertTrue(ScheduledTaskManager.relativeAlarmDue(iv, t0 + 5_000_000))
        assertFalse(ScheduledTaskManager.relativeAlarmDue(iv.copy(triggeredCount = 2), t0 + 5_000_000))
        val after = task(ScheduledTriggerKind.AFTER, delaySec = 60)
        assertTrue(ScheduledTaskManager.relativeAlarmDue(after, t0 + 60_000))
        assertFalse("early", ScheduledTaskManager.relativeAlarmDue(after, t0 + 30_000))
        assertFalse("already fired", ScheduledTaskManager.relativeAlarmDue(after.copy(triggeredCount = 1), t0 + 60_000))
        assertTrue("calendar is not decided here", ScheduledTaskManager.relativeAlarmDue(task(), t0))
    }

    // ── [T-android-scheduled-oncompletion-finish] upstream = whole schedule ─

    @Test
    fun `an upstream finishes when its schedule is over, not after each run`() {
        val f = ScheduledCompletionTriggers::scheduleFinished
        val loop = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 6, triggered = 1)
        assertFalse("check 1 of 6", f(loop, t0))
        assertTrue("the last of --count", f(loop.copy(triggeredCount = 6, enabled = false), t0))
        assertFalse("an endless interval never finishes", f(task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, triggered = 40), t0))
        assertFalse("a daily task never finishes", f(task(repeat = ScheduledRepeatMode.DAILY), t0))
        assertTrue("a calendar once", f(task(repeat = ScheduledRepeatMode.ONCE, enabled = false), t0))
        assertTrue("an --after", f(task(ScheduledTriggerKind.AFTER, delaySec = 60, triggered = 1, enabled = false), t0))
        assertTrue("a chained on-completion", f(task(ScheduledTriggerKind.ON_COMPLETION, of = "x", triggered = 1, enabled = false), t0))
        assertFalse("a deleted upstream releases nothing", f(null, t0))
    }

    @Test
    fun `only a scheduled fire can release the tasks waiting on it`() {
        val runner = com.openminis.app.ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertEquals(2, Regex("""if \(scheduledFire\) ScheduledCompletionTriggers\.onScheduledRunFinished""").findAll(runner).count())
        assertFalse(runner.contains("ScheduledCompletionTriggers.onUpstreamFinished"))
        val receiver = com.openminis.app.ProductionSources.read("scheduled/ScheduledTaskAlarmReceiver.kt")
        assertTrue(receiver.contains("if (!manager.rescheduleNext(taskId)) return@withTimeout"))
        assertTrue(receiver.contains("waitForCompletion = false, scheduledFire = true"))
    }
}
