package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import com.openminis.app.scheduled.ScheduledTaskDetailModel.Delivery
import com.openminis.app.scheduled.ScheduledTaskDetailModel.DurationUnit
import com.openminis.app.scheduled.ScheduledTaskDetailModel.FireState
import com.openminis.app.scheduled.ScheduledTaskDetailModel.Status
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** [T-scheduled-task-detail] The data behind the scheduled-task detail page. */
class ScheduledTaskDetailModelTest {

    /** Today at [h]:[m], local time — the tests reason in wall-clock terms. */
    private fun today(h: Int, m: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun daysFromToday(d: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, d)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val now get() = today(12)

    private fun task(
        mode: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
        enabled: Boolean = true,
        runs: List<ScheduledRun> = emptyList(),
        fireCount: Int? = null,
        end: Long? = null,
        start: Long? = null,
        days: Set<Int> = emptySet(),
        hour: Int = 18,
        minute: Int = 0,
        target: ScheduledTargetMode = ScheduledTargetMode.NewSession,
    ) = ScheduledTask(
        id = "task-1", label = "Disk check", timeOfDayHour = hour, timeOfDayMinute = minute,
        repeatMode = mode, customDays = days, prompt = "Summarise disk usage", targetMode = target,
        enabled = enabled, createdAt = 1L, startDateMs = start, endDateMs = end,
        runHistory = runs, fireCount = fireCount,
    )

    private fun run(at: Long, ok: Boolean = true, sid: String? = "S1") = ScheduledRun(at, sid, "p$at", ok)

    private fun marker(firedAt: Long? = null) =
        ScheduledTaskMarker("task-1", "Disk check", null, "Summarise disk usage", firedAtMs = firedAt)

    // ── status: every row, and never "Interrupted" ──────────────────────────

    @Test
    fun `status matrix`() {
        assertEquals(Status.DELETED, ScheduledTaskDetailModel.status(null, fireRunning = false, now = now))
        assertEquals(Status.RUNNING, ScheduledTaskDetailModel.status(task(), fireRunning = true, now = now))
        assertEquals(Status.WAITING, ScheduledTaskDetailModel.status(task(), false, now))

        // One-shot: waiting, then decided by its single run.
        assertEquals(Status.WAITING, ScheduledTaskDetailModel.status(task(ScheduledRepeatMode.ONCE), false, now))
        assertEquals(
            Status.COMPLETED,
            ScheduledTaskDetailModel.status(task(ScheduledRepeatMode.ONCE, enabled = false, runs = listOf(run(1))), false, now),
        )
        assertEquals(
            Status.FAILED,
            ScheduledTaskDetailModel.status(task(ScheduledRepeatMode.ONCE, enabled = false, runs = listOf(run(1, ok = false))), false, now),
        )
        assertEquals(Status.DISABLED, ScheduledTaskDetailModel.status(task(ScheduledRepeatMode.ONCE, enabled = false), false, now))

        // Repeating: a window that has run out is complete, enabled or not.
        val ended = daysFromToday(-1)
        assertEquals(Status.COMPLETED, ScheduledTaskDetailModel.status(task(end = ended), false, now))
        assertEquals(Status.COMPLETED, ScheduledTaskDetailModel.status(task(enabled = false, end = ended), false, now))
        assertEquals(Status.DISABLED, ScheduledTaskDetailModel.status(task(enabled = false), false, now))
        // A repeating task keeps waiting after a failed run: one bad fire is
        // not the task's state.
        assertEquals(Status.WAITING, ScheduledTaskDetailModel.status(task(runs = listOf(run(1, ok = false))), false, now))
    }

    @Test
    fun `there is no Interrupted status to produce`() {
        assertTrue(Status.values().none { it.name.contains("INTERRUPT") })
    }

    @Test
    fun `a fire is running only while unrecorded and its chat is busy`() {
        val t = task(runs = listOf(run(100)))
        assertTrue(ScheduledTaskDetailModel.isFireRunning(t, marker(200), hostStreaming = true))
        assertFalse("recorded = finished", ScheduledTaskDetailModel.isFireRunning(t, marker(100), true))
        assertFalse("chat idle", ScheduledTaskDetailModel.isFireRunning(t, marker(200), false))
        assertFalse("old envelope without a fire time", ScheduledTaskDetailModel.isFireRunning(t, marker(null), true))
    }

    // ── progress ────────────────────────────────────────────────────────────

    @Test
    fun `a one-shot task counts to one`() {
        val waiting = ScheduledTaskDetailModel.progress(task(ScheduledRepeatMode.ONCE), marker(), false, now)
        assertEquals(1, waiting.total)
        assertEquals(1, waiting.remaining)
        assertEquals(today(18), waiting.nextFireAt)

        val done = ScheduledTaskDetailModel.progress(
            task(ScheduledRepeatMode.ONCE, enabled = false, runs = listOf(run(5))), marker(5), false, now,
        )
        assertEquals(1, done.total)
        assertEquals(0, done.remaining)
        assertEquals(1, done.currentIndex)
        assertNull(done.nextFireAt)
    }

    @Test
    fun `a window with an end date has a finite total`() {
        // Daily 18:00, today through two days from now: today, +1, +2 → 3 to go.
        val p = ScheduledTaskDetailModel.progress(
            task(end = daysFromToday(2), runs = listOf(run(1), run(0)), fireCount = 2), marker(), false, now,
        )
        assertEquals(3, p.remaining)
        assertEquals(5, p.total)
        assertTrue(p.visible)
    }

    @Test
    fun `an open-ended repeating task is unbounded`() {
        val p = ScheduledTaskDetailModel.progress(task(), marker(), false, now)
        assertNull(p.total)
        assertNull(p.remaining)
        assertFalse("nothing to count and no tapped fire", p.visible)
    }

    @Test
    fun `the tapped fire's index counts past the 50-row history cap`() {
        val runs = (0 until 50).map { run(1000L - it) } // newest first
        val t = task(runs = runs, fireCount = 120)
        assertEquals(120, ScheduledTaskDetailModel.progress(t, marker(1000), false, now).currentIndex)
        assertEquals(118, ScheduledTaskDetailModel.progress(t, marker(998), false, now).currentIndex)
        assertEquals("running = next", 121, ScheduledTaskDetailModel.progress(t, marker(2000), true, now).currentIndex)
        assertNull(ScheduledTaskDetailModel.progress(t, marker(null), false, now).currentIndex)
    }

    @Test
    fun `a task written before fireCount falls back to its history length`() {
        assertEquals(2, task(runs = listOf(run(2), run(1))).firesSoFar)
        assertEquals(7, task(runs = listOf(run(2)), fireCount = 7).firesSoFar)
    }

    // ── history merge ───────────────────────────────────────────────────────

    @Test
    fun `history is newest first, numbered, with the tapped fire and other chats marked`() {
        val t = task(runs = listOf(run(300, sid = "S2"), run(200, ok = false), run(100)), fireCount = 3)
        val rows = ScheduledTaskDetailModel.history(t, marker(200), hostSessionId = "S1", fireRunning = false)
        assertEquals(listOf(3, 2, 1), rows.map { it.index })
        assertEquals(listOf(FireState.OK, FireState.FAILED, FireState.OK), rows.map { it.state })
        assertEquals(listOf(false, true, false), rows.map { it.isCurrent })
        assertEquals(listOf(true, false, false), rows.map { it.inOtherSession })
    }

    @Test
    fun `a still-running fire is listed first, once`() {
        val t = task(runs = listOf(run(100)), fireCount = 1)
        val rows = ScheduledTaskDetailModel.history(t, marker(500), "S1", fireRunning = true)
        assertEquals(2, rows.size)
        assertEquals(FireState.RUNNING, rows[0].state)
        assertEquals(2, rows[0].index)
        assertTrue(rows[0].isCurrent)
        // Once recorded it is not duplicated.
        val recorded = task(runs = listOf(run(500), run(100)), fireCount = 2)
        assertEquals(2, ScheduledTaskDetailModel.history(recorded, marker(500), "S1", fireRunning = true).size)
    }

    @Test
    fun `a deleted task has no history of its own`() {
        assertTrue(ScheduledTaskDetailModel.history(null, marker(1), "S1", false).isEmpty())
    }

    // ── trigger text ────────────────────────────────────────────────────────

    private val en = ScheduledTaskDetailModel.TriggerPhrases(
        once = "Once at %1\$s", daily = "Every day %1\$s", weekdays = "Weekdays %1\$s",
        weekly = "Every %1\$s %2\$s", daySeparator = ", ",
        dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
        from = "from %1\$s", until = "until %1\$s", window = "%1\$s – %2\$s",
    )
    private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
    private fun text(t: ScheduledTask) = ScheduledTaskDetailModel.triggerText(t, en, ::hm) { "D$it" }

    @Test
    fun `trigger text for every repeat mode`() {
        assertEquals("Once at 08:05", text(task(ScheduledRepeatMode.ONCE, hour = 8, minute = 5)))
        assertEquals("Once at D7 08:05", text(task(ScheduledRepeatMode.ONCE, hour = 8, minute = 5, start = 7)))
        assertEquals("Every day 18:00", text(task()))
        assertEquals("Weekdays 18:00", text(task(ScheduledRepeatMode.WEEKDAYS)))
        // Monday first, whatever order the set holds.
        assertEquals(
            "Every Mon, Wed, Sun 09:30",
            text(task(ScheduledRepeatMode.CUSTOM, days = setOf(Calendar.SUNDAY, Calendar.WEDNESDAY, Calendar.MONDAY), hour = 9, minute = 30)),
        )
    }

    @Test
    fun `trigger text shows the active window`() {
        assertEquals("Every day 18:00 · until D9", text(task(end = 9)))
        assertEquals("Every day 18:00 · from D3", text(task(start = 3)))
        assertEquals("Every day 18:00 · D3 – D9", text(task(start = 3, end = 9)))
    }

    // ── chat card subtitle ──────────────────────────────────────────────────

    @Test
    fun `a card whose next-fire time has passed says nothing, not last run`() {
        val sub = ScheduledTaskDetailModel::cardSubtitle
        val m = ScheduledTaskMarker("T", "L", nextFireAtMs = 1_000L, prompt = "p")
        assertEquals(ScheduledTaskDetailModel.CardSubtitle.Next(1_000L), sub(m, false, 500L))
        assertEquals(ScheduledTaskDetailModel.CardSubtitle.None, sub(m, false, 1_000L))
        assertEquals(ScheduledTaskDetailModel.CardSubtitle.None, sub(m, false, 5_000L))
        // Only a fire with no next one is the last run.
        val last = ScheduledTaskMarker("T", "L", nextFireAtMs = null, prompt = "p")
        assertEquals(ScheduledTaskDetailModel.CardSubtitle.LastRun, sub(last, false, 5_000L))
        assertEquals(ScheduledTaskDetailModel.CardSubtitle.Cancelled, sub(m, true, 500L))
    }

    @Test
    fun `the card uses the subtitle rule`() {
        val card = ProductionSources.read("ui/chat/ScheduledTaskCard.kt")
        assertTrue(card.contains("ScheduledTaskDetailModel.cardSubtitle(marker, cancelled, now)"))
        assertTrue(card.contains("ScheduledTaskDetailModel.CardSubtitle.None -> null"))
    }

    @Test
    fun `the card re-reads the clock when its next-fire time passes`() {
        // Read only at composition, an on-screen card kept "Next: <time>"
        // after the fire had happened.
        val card = ProductionSources.read("ui/chat/ScheduledTaskCard.kt")
        assertTrue(card.contains("produceState(System.currentTimeMillis(), marker.nextFireAtMs)"))
        assertTrue(card.contains("delay(wait + 1)"))
    }

    @Test
    fun `the detail sheet pins its nav bar and stays below the status bar`() {
        // Sized to its content, a long page slid under the status bar and the
        // nav bar scrolled away with the cards.
        val sheet = ProductionSources.read("ui/chat/ScheduledTaskDetailSheet.kt")
        assertTrue(sheet.contains("Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f))"))
        assertTrue(sheet.contains(".weight(1f)\n                    .verticalScroll(rememberScrollState())"))
    }

    // ── durations ───────────────────────────────────────────────────────────

    @Test
    fun `durations use their two largest units`() {
        assertEquals(listOf(DurationUnit.SECOND to 45), ScheduledTaskDetailModel.durationParts(45))
        assertEquals(listOf(DurationUnit.MINUTE to 1, DurationUnit.SECOND to 30), ScheduledTaskDetailModel.durationParts(90))
        assertEquals(listOf(DurationUnit.HOUR to 1, DurationUnit.MINUTE to 2), ScheduledTaskDetailModel.durationParts(3725))
        assertEquals(listOf(DurationUnit.HOUR to 2), ScheduledTaskDetailModel.durationParts(7200))
        assertEquals(listOf(DurationUnit.DAY to 1, DurationUnit.HOUR to 1), ScheduledTaskDetailModel.durationParts(90_000))
        assertEquals(listOf(DurationUnit.SECOND to 0), ScheduledTaskDetailModel.durationParts(0))
    }

    // ── prompt ──────────────────────────────────────────────────────────────

    @Test
    fun `the prompt is cleaned of every leading note`() {
        assertEquals("Summarise disk usage", ScheduledTaskDetailModel.cleanPrompt(task(), marker()))
        val noisy = ScheduledTaskMarker(
            "gone", "", null,
            "[Scheduled task \"x\" · fire 2 · remaining 28 · loop(60s×30)] " +
                "[This task's first step already ran for you: shell_execute — its call and output follow. " +
                "Work from that output; re-run only if it failed.]\nCheck the logs",
        )
        assertEquals("Check the logs", ScheduledTaskDetailModel.cleanPrompt(null, noisy))
        val reminded = ScheduledTaskMarker("gone", "", null, "<system-reminder>x</system-reminder>\nHello")
        assertEquals("Hello", ScheduledTaskDetailModel.cleanPrompt(null, reminded))
        val plain = ScheduledTaskMarker("gone", "", null, "[not a note] keep me")
        assertEquals("[not a note] keep me", ScheduledTaskDetailModel.cleanPrompt(null, plain))
    }

    @Test
    fun `an inserted envelope's prompt comes back clean from the marker`() {
        val xml = ScheduledTaskMarker("gone", "L", null, "Hello", prefilledTool = "shell_execute", insertedMidTask = true).xml
        assertEquals("Hello", ScheduledTaskDetailModel.cleanPrompt(null, ScheduledTaskMarker.parse(xml)!!))
    }

    // ── delivery ────────────────────────────────────────────────────────────

    @Test
    fun `delivery reads relative to the chat the page was opened from`() {
        val d = ScheduledTaskDetailModel::delivery
        assertEquals(Delivery.NewSession, d(ScheduledTargetMode.NewSession, "S1"))
        assertEquals(Delivery.ThisChat, d(ScheduledTargetMode.AppendToSession("S1"), "S1"))
        assertEquals(Delivery.OtherChat("S9"), d(ScheduledTargetMode.AppendToSession("S9"), "S1"))
        assertEquals(Delivery.Rerun("S1", true), d(ScheduledTargetMode.RerunMessage("S1", "M"), "S1"))
        assertEquals(Delivery.Rerun("S9", false), d(ScheduledTargetMode.RerunMessage("S9", "M"), "S1"))
        assertEquals(Delivery.ChildOfThisChat, d(ScheduledTargetMode.ChildOfCurrent("S1"), "S1"))
        assertEquals(Delivery.ChildOfOtherChat("S9"), d(ScheduledTargetMode.ChildOfCurrent("S9"), null))
    }

    // ── persistence the page relies on ──────────────────────────────────────

    @Test
    fun `the envelope carries its fire time and parses it back`() {
        val m = ScheduledTaskMarker("T", "L", 5L, "p", firedAtMs = 1234L)
        assertTrue(m.xml.contains(" fired=\"1234\""))
        assertEquals(1234L, ScheduledTaskMarker.parse(m.xml)!!.firedAtMs)
        assertNull(ScheduledTaskMarker.parse(ScheduledTaskMarker("T", "L", 5L, "p").xml)!!.firedAtMs)
    }

    @Test
    fun `fireCount round-trips and is absent on old rows`() {
        assertEquals(12, ScheduledTask.fromJson(task(fireCount = 12).toJson()).fireCount)
        val json = task().toJson()
        assertFalse(json.has("fireCount"))
        assertNull(ScheduledTask.fromJson(JSONObject(json.toString())).fireCount)
    }

    @Test
    fun `one timestamp ties a fire's envelope to its run record`() {
        val runner = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertTrue(runner.contains("val firedAt = System.currentTimeMillis()"))
        assertTrue(runner.contains("firedAtMs = firedAt,"))
        assertEquals(2, Regex("""markFired\(task\.id, sessionId, preview, ok = ok, firedAt = firedAt\)""").findAll(runner).count())
        val manager = ProductionSources.read("scheduled/ScheduledTaskManager.kt")
        assertTrue(manager.contains("fireCount = t.firesSoFar + 1,"))
        assertTrue(manager.contains("val now = firedAt"))
    }

    @Test
    fun `the chat card opens the detail page`() {
        val card = ProductionSources.read("ui/chat/ScheduledTaskCard.kt")
        assertTrue(card.contains(".clickable { showingDetail = true }"))
        assertTrue(card.contains("ScheduledTaskDetailSheet(\n            marker = marker,\n            hostSessionId = hostSessionId,"))
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(chat.contains("marker = item.marker,\n                                hostSessionId = sessionId,"))
    }
}
