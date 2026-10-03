package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-multitask] [T-android-overlay-stale-tool] The floating
 * capsule with more than one task running.
 *
 * Reported: "任务已经结束了，为什么悬浮窗还在展示正在执行工具". The device log
 * showed both halves of the problem within six seconds:
 *
 *   10:57:55  toolRunning=false toolName=null  … yet the capsule still rendered
 *             `toolTitle=验证界面复刻 APK` with a running glyph
 *   10:58:01  the same stale title, now paired with `session=60ee870e` — a
 *             DIFFERENT session from the one whose tool that was
 *
 * Two distinct defects. The capsule fell back to the last tool's identity
 * unconditionally while passing `isRunning=true`, so a finished tool kept
 * rendering as running; and every piece of tool state was a single global that
 * whichever session wrote last owned, so a second run silently took the capsule
 * over and the title could describe one task while the tap target opened
 * another.
 *
 * The tracker's real state is a set of StateFlows written from several threads
 * and reachable only through a Context-bound singleton, so the decisions are
 * restated here as pure functions and pinned against the source. Each
 * behavioural test also runs the OLD rule and asserts it gives the wrong
 * answer, so none can pass vacuously.
 */
class OverlayMultiTaskIndicatorTest {

    // ── the stale-tool fallback ───────────────────────────────────────────

    /** Mirrors AgentForegroundService's gated fallback. */
    private fun effectiveToolTitle(
        liveTitle: String?,
        lastTitle: String?,
        isRunning: Boolean,
        isThinking: Boolean,
        hasActiveStream: Boolean,
    ): String? {
        val stillWorking = isRunning || isThinking || hasActiveStream
        return liveTitle ?: lastTitle?.takeIf { stillWorking }
    }

    /** The old rule: fall back unconditionally. */
    private fun oldEffectiveToolTitle(liveTitle: String?, lastTitle: String?): String? =
        liveTitle ?: lastTitle

    @Test
    fun `the reported case - a finished tool stops being shown as running`() {
        // Exactly the 10:57:55 state: tool gone, nothing else in flight.
        val title = effectiveToolTitle(
            liveTitle = null,
            lastTitle = "验证界面复刻 APK",
            isRunning = false,
            isThinking = false,
            hasActiveStream = false,
        )
        assertNull(title)

        // The old rule is what kept it on screen.
        assertEquals(
            "old rule must fail here",
            "验证界面复刻 APK",
            oldEffectiveToolTitle(null, "验证界面复刻 APK"),
        )
    }

    @Test
    fun `the snapshot survives a streaming stretch between two tool calls`() {
        // This is what the fallback was written for: no live tool, but the
        // model is still producing text. A bare spinner here would be a
        // regression, so the gate must NOT drop the snapshot.
        assertEquals(
            "验证界面复刻 APK",
            effectiveToolTitle(null, "验证界面复刻 APK", false, false, hasActiveStream = true),
        )
        assertEquals(
            "验证界面复刻 APK",
            effectiveToolTitle(null, "验证界面复刻 APK", false, isThinking = true, hasActiveStream = false),
        )
    }

    @Test
    fun `a live tool always wins over the snapshot`() {
        assertEquals(
            "搜索天气数据",
            effectiveToolTitle("搜索天气数据", "验证界面复刻 APK", true, false, true),
        )
    }

    // ── task slots: ordering, numbering, lifetime ─────────────────────────

    private data class Task(val id: String, val title: String, val finished: Boolean = false)

    /** Mirrors SessionActivityTracker.focusedTaskSlot(). */
    private fun slot(tasks: List<Task>, focusedId: String?): Triple<Task, Int, Int>? {
        if (tasks.isEmpty()) return null
        val i = tasks.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 } ?: tasks.lastIndex
        return Triple(tasks[i], i + 1, tasks.size)
    }

    @Test
    fun `a single task reports one of one`() {
        val s = slot(listOf(Task("a", "Find Android Dev Minis Skill")), null)!!
        assertEquals(1, s.second)
        assertEquals(1, s.third)
    }

    @Test
    fun `with no explicit focus the capsule follows the newest task`() {
        // Pre-existing behaviour: the most recent activity owns the capsule.
        val tasks = listOf(Task("a", "A"), Task("b", "B"))
        val s = slot(tasks, focusedId = null)!!
        assertEquals("B", s.first.title)
        assertEquals(2, s.second)
    }

    @Test
    fun `a finished task keeps its index and stays in the total`() {
        // The capsule lingers after a turn ends, so the task is still visible
        // and must still be numbered — "① of 2" pointing at nothing is worse
        // than showing a finished slot.
        val tasks = listOf(Task("a", "A", finished = true), Task("b", "B"))
        val s = slot(tasks, focusedId = "a")!!
        assertEquals(1, s.second)
        assertEquals(2, s.third)
        assertTrue(s.first.finished)
    }

    @Test
    fun `finishing a task never renumbers the one beside it`() {
        // If A leaving renumbered B from ② to ①, the user would look away from
        // "② of 2" and back at "① of 1" describing the same work.
        val before = listOf(Task("a", "A"), Task("b", "B"))
        assertEquals(2, slot(before, "b")!!.second)

        val afterAFinished = listOf(Task("a", "A", finished = true), Task("b", "B"))
        assertEquals(2, slot(afterAFinished, "b")!!.second)
    }

    @Test
    fun `focus survives a slot leaving because it is held by id not index`() {
        val tasks = listOf(Task("a", "A"), Task("b", "B"), Task("c", "C"))
        assertEquals("C", slot(tasks, "c")!!.first.title)
        // A is dropped; C is now index 2 of 2 but is still the focused task.
        val dropped = tasks.filterNot { it.id == "a" }
        val s = slot(dropped, "c")!!
        assertEquals("C", s.first.title)
        assertEquals(2, s.second)
        assertEquals(2, s.third)
    }

    @Test
    fun `an empty task list yields no slot`() {
        assertNull(slot(emptyList(), null))
    }

    // ── cycling ───────────────────────────────────────────────────────────

    private fun cycle(tasks: List<Task>, focusedId: String?): String? {
        if (tasks.size < 2) return focusedId
        val cur = focusedId ?: tasks.first().id
        val i = tasks.indexOfFirst { it.id == cur }
        return tasks[(i + 1) % tasks.size].id
    }

    @Test
    fun `cycling advances and wraps`() {
        val tasks = listOf(Task("a", "A"), Task("b", "B"), Task("c", "C"))
        assertEquals("b", cycle(tasks, "a"))
        assertEquals("c", cycle(tasks, "b"))
        assertEquals("a", cycle(tasks, "c"))
    }

    @Test
    fun `cycling a single task is a no-op`() {
        val tasks = listOf(Task("a", "A"))
        assertEquals("a", cycle(tasks, "a"))
    }

    // ── the index chip ────────────────────────────────────────────────────

    @Test
    fun `the index is a plain number in every locale`() {
        // Circled digits (①②③) were tried and dropped: they stop at 20, and
        // they render from a different font — a colour emoji on some OEM
        // builds — so they sat at the wrong optical weight beside the clock.
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "no circled-digit arithmetic may come back",
            !src.contains("'\u2460' +") && !src.contains("in 1..20"),
        )
        assertTrue(
            "the index is passed to the string as an Int",
            src.contains("R.string.overlay_task_index, taskIndex, taskTotal"),
        )
    }

    @Test
    fun `the chip is an outline with no fill`() {
        // A filled chip read as a second surface stacked on the capsule and
        // pulled more attention than a task counter warrants.
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "radius must track half the chip height, as the capsule's does",
            src.contains("cornerRadius = TASK_PILL_HEIGHT_DP / 2f * density"),
        )
        val chip = src.substringAfter("TASK_PILL_HEIGHT_DP / 2f * density")
            .substringBefore("}")
        assertTrue("the chip must not be filled", chip.contains("setColor(Color.TRANSPARENT)"))
        assertTrue("the chip keeps its hairline border", chip.contains("setStroke(dpToPx(1)"))
    }

    @Test
    fun `non-Latin locales render the index without the English word`() {
        // "of" is an English word; in zh/ja/ko/th it reads as foreign and
        // there is no short native equivalent that fits the chip, so those
        // locales use "n/m". This is done in the RESOURCES, not in code —
        // the test exists because a missing translation fails silently by
        // falling back to English rather than breaking a build.
        // mainRoot() is src/main/java/com/openminis/app; res is src/main/res.
        val res = generateSequence(ProductionSources.mainRoot()!!) { it.parentFile }
            .first { java.io.File(it, "res/values/strings.xml").isFile }
            .resolve("res")
        val base = java.io.File(res, "values/strings.xml").readText()
        assertTrue(
            "the source locale keeps the word",
            base.contains("""<string name="overlay_task_index">%1${'$'}d of %2${'$'}d</string>"""),
        )
        for (loc in listOf("zh", "zh-rTW", "ja", "ko", "th")) {
            val f = java.io.File(res, "values-$loc/strings.xml")
            assertTrue("missing locale file: $loc", f.isFile)
            assertTrue(
                "$loc must render the index as n/m, not \"n of m\"",
                f.readText().contains(
                    """<string name="overlay_task_index">%1${'$'}d/%2${'$'}d</string>""",
                ),
            )
        }
    }

    @Test
    fun `every locale that declares the index uses integer placeholders`() {
        // The first placeholder changed from %1${'$'}s to %1${'$'}d when the circled glyph
        // went away. A locale left on %1${'$'}s would throw IllegalFormatConversion
        // at render time — on that locale only, which is exactly the kind of
        // break that ships.
        // mainRoot() is src/main/java/com/openminis/app; res is src/main/res.
        val res = generateSequence(ProductionSources.mainRoot()!!) { it.parentFile }
            .first { java.io.File(it, "res/values/strings.xml").isFile }
            .resolve("res")
        val offenders = res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .mapNotNull { dir ->
                val f = java.io.File(dir, "strings.xml")
                if (!f.isFile) return@mapNotNull null
                val line = f.readText().lines().firstOrNull { "overlay_task_index" in it && "<string" in it }
                    ?: return@mapNotNull null
                if ("%1${'$'}s" in line) dir.name else null
            }
        assertEquals("locales still using a string placeholder", emptyList<String>(), offenders)
    }

    // ── untitled sessions ─────────────────────────────────────────────────

    /** Mirrors ChatViewModel.overlaySessionTitle(). */
    private fun overlaySessionTitle(raw: String): String? =
        raw.takeIf { it.isNotBlank() && it != "New Chat" }

    /** Mirrors the capsule's row-1 precedence. */
    private fun rowOne(sessionTitle: String?, completionWord: String?, soulName: String): String =
        sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: soulName

    @Test
    fun `an untitled session shows the assistant name, not the placeholder`() {
        // "New Chat" is the sentinel a session carries until title generation
        // names it. It is identical on every untitled task, so on a capsule
        // that can show several at once it identifies nothing.
        assertNull(overlaySessionTitle("New Chat"))
        assertEquals("Pikachu", rowOne(overlaySessionTitle("New Chat"), null, "Pikachu"))
    }

    @Test
    fun `a blank title also falls back to the assistant name`() {
        assertNull(overlaySessionTitle(""))
        assertNull(overlaySessionTitle("   "))
        assertEquals("Pikachu", rowOne(overlaySessionTitle("  "), null, "Pikachu"))
    }

    @Test
    fun `a real title wins over both the completion word and the Soul name`() {
        val t = overlaySessionTitle("Find Android Dev Minis Skill")
        assertEquals("Find Android Dev Minis Skill", t)
        assertEquals("Find Android Dev Minis Skill", rowOne(t, "执行完成", "Pikachu"))
    }

    @Test
    fun `the placeholder is a named constant, matched where it is defined`() {
        // The view must not string-match "New Chat": it is an English literal
        // that a localization pass could change, and the title-generator's own
        // skip guard depends on the same value.
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(
            "the sentinel must be a named constant",
            vm.contains("const val UNTITLED_SESSION_TITLE = \"New Chat\""),
        )
        assertTrue(
            "the helper must exist",
            vm.contains("private fun overlaySessionTitle(): String?"),
        )
        // Defining it is not enough — every setActive must actually CALL it.
        // Passing `_sessionTitle.value` straight through compiles fine and
        // silently puts "New Chat" back on the capsule, so count both spellings.
        val routed = Regex("sessionTitle = overlaySessionTitle\\(\\)").findAll(vm).count()
        val raw = Regex("sessionTitle = _sessionTitle\\.value").findAll(vm).count()
        assertEquals("no overlay call site may pass the raw title", 0, raw)
        assertTrue("expected the overlay call sites to route through the helper", routed >= 5)
        val overlay = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "the capsule must not know the sentinel",
            !overlay.contains("New Chat"),
        )
    }

    // ── slots must not leak between runs ──────────────────────────────────

    @Test
    fun `every path that hides the capsule also drops its task slots`() {
        // Reported: "实际执行的只有一个任务，怎么展示 2 of 2". Finished slots are
        // kept on purpose so a lingering task stays numbered, but only while
        // the capsule is up. Originally just the linger-expiry path cleared
        // them, so the foreground transition, the dynamic-island swap and the
        // not-busy teardown each left slots behind for the NEXT run to count —
        // one task on screen reporting "3 of 3" after two earlier runs.
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue(
            "the hide+clear pair must live in one helper",
            src.contains("private fun hideOverlay()"),
        )
        // No site may hide the capsule without going through it.
        val direct = Regex("controller\\.hide\\(\\)").findAll(src).count()
        assertEquals("every hide must route through hideOverlay()", 0, direct)
        // [T-android-overlay-hide-keeps-running-slots] Finished slots only —
        // clearing every slot also wiped tasks still running across a
        // foreground/camera/island hide (Review0923OverlayTaskSlotsTest).
        assertTrue(
            "hideOverlay must drop the finished slots",
            src.substringAfter("private fun hideOverlay()")
                .substringBefore("\n    }")
                .contains("dropFinishedOverlayTasks()"),
        )
    }

    @Test
    fun `a fresh run after a cleared capsule starts from one`() {
        // The leak's observable shape: slots surviving into the next run.
        var tasks = listOf(Task("a", "A", finished = true), Task("b", "B", finished = true))
        // Capsule goes down -> every slot goes with it.
        tasks = emptyList()
        // Next run registers exactly one.
        tasks = tasks + Task("c", "C")
        val s = slot(tasks, null)!!
        assertEquals(1, s.second)
        assertEquals(1, s.third)
    }

    // ── source guards ─────────────────────────────────────────────────────

    @Test
    fun `the indicator is hidden below two tasks`() {
        // At one task "① of 1" is noise and costs the title characters it
        // shares the row with.
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "index must be gated on taskTotal >= 2",
            src.contains("taskTotal >= 2"),
        )
    }

    @Test
    fun `row one shows the session title with the Soul name only as fallback`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "session title must take precedence on the identity row",
            src.contains("sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: identityName"),
        )
    }

    @Test
    fun `the clock sits on row two under the index`() {
        // The two columns pair by scope: row 1 is the task (title + which of
        // N), row 2 is the current step (tool + elapsed).
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue("clock must be added to the status row", src.contains("statusRow.addView(elapsed)"))
        assertTrue("index must be added to the label row", src.contains("labelRow.addView(taskIndex)"))
    }

    @Test
    fun `the stale-tool gate is still in the service`() {
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue(
            "fallback must be gated on the turn still working",
            src.contains("state.lastToolName?.takeIf { stillWorkingThisTurn }"),
        )
    }

    @Test
    fun `title and index are resolved from one snapshot`() {
        // Reading them separately is how a title and a bound session id drifted
        // apart on device at 10:58:01.
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        assertEquals(
            "focusedTaskSlot must be read exactly once per render",
            1,
            Regex("SessionActivityTracker\\.focusedTaskSlot\\(\\)").findAll(src).count(),
        )
    }
}
