package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-empty-turn-reminder-any-tail] The agent loop must recover from an
 * empty model turn regardless of what the history tail is — not only when the
 * tail is a tool_result.
 *
 * The trigger that motivated this: a request ending on a finished sub-agent
 * callback (`<agent_callback kind="finished">…</agent_callback>`) returned an
 * empty turn ~7/10 times on GPT-5.6 Terra (measured live), so Retry kept
 * showing "Model returned an empty response". Before the fix the recovery
 * guard checked `prior row is all-tool_result`, so the callback tail — a user
 * TEXT row — fell straight through to the error hint with no retry.
 *
 * These pin [EmptyTurnRecovery.plan], the decision the loop delegates to. The
 * loop itself needs a live ViewModel; the placement decision does not, so
 * (as with FallbackPulseGate / ImageInputPreflight) the DECISION is tested
 * here and the loop is a thin adapter over it.
 */
class EmptyTurnRecoveryTest {

    private fun user(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun userText(t: String) =
        LLMMessage(role = LLMMessage.Role.USER, content = t, contentParts = listOf(AgentContentPart.Text(t)))

    private fun assistant(t: String = "") =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = t, contentParts = if (t.isEmpty()) emptyList() else listOf(AgentContentPart.Text(t)))

    private fun toolResult(id: String, out: String) =
        user(AgentContentPart.ToolResult(id = id, name = "shell_execute", content = out))

    private fun toolUse(id: String) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", JSONObject())))

    /** The empty assistant turn the loop appends before calling plan(). */
    private val emptyAssistant = assistant("")

    private val REAL_CALLBACK =
        "<agent_callback kind=\"finished\" job=\"09ed2635\" title=\"总结苹果维基前三段\" status=\"done\">\n" +
            "<result>前三段总结……</result>\n</agent_callback>"

    // ── the reported failure: callback tail ────────────────────────────────

    @Test
    fun `a finished-callback tail is nudged, not given up on`() {
        // Sanity: the fixture really is a callback the app recognises.
        assertTrue(AgentCallback.isCallbackText(REAL_CALLBACK))

        val history = listOf(
            userText("请派一个子代理…"),
            assistant("已派出"),
            userText(REAL_CALLBACK),   // the orphan callback tail
            emptyAssistant,            // loop appended this before recovery
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertTrue("callback tail must be nudged", plan is EmptyTurnRecovery.Plan.NudgeExistingPart)
        plan as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals("nudge the callback user row (index 2)", 2, plan.tailIndex)
        assertEquals(false, plan.isToolResult)
    }

    @Test
    fun `the nudge lands on the callback text and reads as continue`() {
        val history = listOf(userText(REAL_CALLBACK), emptyAssistant)
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false) as EmptyTurnRecovery.Plan.NudgeExistingPart
        // Apply the placement the loop would apply.
        val tail = history[plan.tailIndex]
        val part = tail.contentParts[plan.partIndex] as AgentContentPart.Text
        val nudged = part.text + EmptyTurnRecovery.REMINDER
        assertTrue(nudged.startsWith(REAL_CALLBACK))
        assertTrue(nudged.contains("<system-reminder>"))
        assertTrue(nudged.contains("Do not return an empty response"))
    }

    // ── the original case must be unchanged ────────────────────────────────

    @Test
    fun `a tool_result tail still nudges the tool_result (byte-identical intent)`() {
        val history = listOf(
            toolUse("call_1"),
            toolResult("call_1", "ok"),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertTrue(plan is EmptyTurnRecovery.Plan.NudgeExistingPart)
        plan as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals(1, plan.tailIndex)
        assertEquals("tool_result must win over any text part", true, plan.isToolResult)
    }

    @Test
    fun `tool_result is preferred when the tail carries both a text and a tool_result part`() {
        val history = listOf(
            user(AgentContentPart.Text("here"), AgentContentPart.ToolResult("call_1", "shell_execute", "ok")),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false) as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals(true, plan.isToolResult)
    }

    // ── other statement-shaped tails (systematic coverage) ─────────────────

    @Test
    fun `a plain statement-shaped user notice with no question is nudged`() {
        // e.g. a scheduled-task trigger notice, or any auto-injected text.
        val history = listOf(userText("[Scheduled task fired: nightly summary is ready.]"), emptyAssistant)
        assertTrue(EmptyTurnRecovery.plan(history, alreadyFired = false) is EmptyTurnRecovery.Plan.NudgeExistingPart)
    }

    @Test
    fun `an empty-output tool_result is still nudged`() {
        val history = listOf(toolUse("call_1"), toolResult("call_1", ""), emptyAssistant)
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertTrue(plan is EmptyTurnRecovery.Plan.NudgeExistingPart)
        assertEquals(true, (plan as EmptyTurnRecovery.Plan.NudgeExistingPart).isToolResult)
    }

    @Test
    fun `an image-only user tail with no appendable part gets a standalone nudge`() {
        val history = listOf(
            user(AgentContentPart.ImageData(byteArrayOf(1, 2, 3), "image/png")),
            emptyAssistant,
        )
        assertEquals(EmptyTurnRecovery.Plan.AppendStandalone, EmptyTurnRecovery.plan(history, alreadyFired = false))
    }

    // ── the guards: must NOT fire ──────────────────────────────────────────

    @Test
    fun `once fired in a run, it never fires again (no loop)`() {
        val history = listOf(userText(REAL_CALLBACK), emptyAssistant)
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(history, alreadyFired = true))
    }

    @Test
    fun `two empty turns back to back give up rather than fabricate a turn`() {
        // Tail (row -2) is an assistant row: the model stopped after its own
        // turn with no user message to nudge. Fabricating one would be wrong.
        val history = listOf(userText("hi"), assistant("some text"), emptyAssistant)
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(history, alreadyFired = false))
    }

    // ── stop-reason narrowing (iOS reasoning-only parity) ─────────────────

    @Test
    fun `a reasoning-only turn that STOPPED is empty and gets recovered`() {
        // No text, no tool, finished on stop/end_turn — the stall the user sees
        // as "the run just ended". This IS empty on purpose.
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "stop"))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "end_turn"))
        // null stop = dropped stream the caller already decided to treat as empty.
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = null))
    }

    @Test
    fun `an interleaved-thinking turn (reasoning then tool call) is NOT empty`() {
        // The case the iOS advice protects: reasoning that led to a tool call
        // must not be judged empty and re-run. On this API that turn ends on
        // tool_use / carries a tool call, so it is excluded here.
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = true, stopReason = "tool_use"))
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = true, stopReason = "stop"))
    }

    @Test
    fun `a tool_calls or tool_use stop with no call and no text IS empty`() {
        // [T-android-tooluse-stop-no-calls] The finish reason promised a tool
        // round but nothing arrived to execute — without this the loop broke out
        // of `toolCalls.isEmpty()` and the run ended silently (iOS 910708619).
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "tool_calls"))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "tool_use"))
        // Text or a real call still wins.
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = true, hasToolCall = false, stopReason = "tool_calls"))
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = true, stopReason = "tool_calls"))
    }

    @Test
    fun `a truncated turn (length) is NOT classified empty`() {
        // max_output_tokens / length: partial output is kept upstream; never
        // route it through empty recovery even with no text captured here.
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "length"))
    }

    @Test
    fun `a turn with text is never empty regardless of stop reason`() {
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = true, hasToolCall = false, stopReason = "stop"))
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = true, hasToolCall = false, stopReason = null))
    }

    @Test
    fun `a history too short to have a tail gives up`() {
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(listOf(emptyAssistant), alreadyFired = false))
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(emptyList(), alreadyFired = false))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // [T04] Empty-turn decision matrix — the combinations not yet locked
    // (627f7c403, 5af31fe21, 27e19cfa9, 5d586a6b9, 56f00191f, 3de9441f2;
    // issues #262 #263 #112). Each axis was relaxed once already; these keep
    // the next relaxation of one axis from re-opening another.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Mirrors the loop's mutation for a [EmptyTurnRecovery.Plan] (ChatViewModel
     * runAgentLoop, `when (val plan = EmptyTurnRecovery.plan(...))`): drop the
     * empty assistant row, then append REMINDER to the chosen part or add a
     * standalone user row. Kept verbatim so the one-shot + placement rules can
     * be exercised end to end without a ViewModel.
     */
    private fun applyPlan(history: MutableList<LLMMessage>, plan: EmptyTurnRecovery.Plan) {
        when (plan) {
            is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
                history.removeAt(history.size - 1)
                val tail = history[plan.tailIndex]
                val newParts = tail.contentParts.toMutableList()
                newParts[plan.partIndex] = when (val part = newParts[plan.partIndex]) {
                    is AgentContentPart.ToolResult -> part.copy(content = part.content + EmptyTurnRecovery.REMINDER)
                    is AgentContentPart.Text -> AgentContentPart.Text(part.text + EmptyTurnRecovery.REMINDER)
                    else -> part
                }
                history[plan.tailIndex] = tail.copy(contentParts = newParts)
            }
            is EmptyTurnRecovery.Plan.AppendStandalone -> {
                history.removeAt(history.size - 1)
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = EmptyTurnRecovery.REMINDER,
                        contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER)),
                    ),
                )
            }
            EmptyTurnRecovery.Plan.GiveUp -> Unit
        }
    }

    private fun reminderCount(history: List<LLMMessage>): Int =
        history.sumOf { m ->
            m.contentParts.sumOf { p ->
                when (p) {
                    is AgentContentPart.Text -> p.text.windowed(EmptyTurnRecovery.REMINDER.length).count { it == EmptyTurnRecovery.REMINDER }
                    is AgentContentPart.ToolResult -> p.content.windowed(EmptyTurnRecovery.REMINDER.length).count { it == EmptyTurnRecovery.REMINDER }
                    else -> 0
                }
            }
        }

    /** Image-only user tail + empty reply: treated as a statement, reminder injected as its own row. */
    @Test
    fun `image-only tail - the standalone reminder is a user text row placed after the image`() {
        val history = mutableListOf(
            userText("what is this?"),
            assistant("a chart"),
            user(AgentContentPart.ImageData(byteArrayOf(9, 9, 9), "image/jpeg")),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertEquals(EmptyTurnRecovery.Plan.AppendStandalone, plan)
        applyPlan(history, plan)
        assertEquals("empty assistant dropped, reminder row appended", 4, history.size)
        val tail = history.last()
        assertEquals(LLMMessage.Role.USER, tail.role)
        assertTrue(tail.contentParts.single() is AgentContentPart.Text)
        assertTrue("the image row is untouched", history[2].contentParts.single() is AgentContentPart.ImageData)
        assertEquals(1, reminderCount(history))
    }

    /**
     * Callback + real user text in the same tail row. The nudge lands on the
     * LAST text part (the user's own words), never on the callback body — the
     * callback is not what the model is being asked to answer.
     */
    @Test
    fun `callback followed by real user text - the user's text is nudged, not the callback`() {
        val history = mutableListOf(
            user(AgentContentPart.Text(REAL_CALLBACK), AgentContentPart.Text("please summarise it in one line")),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false) as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals("indexOfLast — the user's text, not the callback", 1, plan.partIndex)
        assertFalse(plan.isToolResult)
        applyPlan(history, plan)
        val parts = history[0].contentParts.filterIsInstance<AgentContentPart.Text>()
        assertEquals("callback body left byte-identical", REAL_CALLBACK, parts[0].text)
        assertTrue(parts[1].text.endsWith(EmptyTurnRecovery.REMINDER))
        assertEquals(1, reminderCount(history))
    }

    /**
     * stop=length / max_tokens with no text is NOT empty: it is the truncated
     * path (partial output kept upstream, dynamicMaxTokens territory). Routing
     * it through recovery would re-run a turn that simply ran out of budget.
     */
    @Test
    fun `stop=length or max_tokens with no text takes the truncation path, not recovery`() {
        for (reason in listOf("length", "max_tokens", "max_output_tokens")) {
            assertFalse("$reason must not be classified empty",
                EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = reason))
        }
    }

    /**
     * stop=refusal (and content_filter): the model DECLINED. Nudging it to
     * "continue" would argue with a refusal; this is an ordinary error, not
     * an empty turn.
     */
    @Test
    fun `stop=refusal is not empty and is never nudged`() {
        for (reason in listOf("refusal", "content_filter", "safety")) {
            assertFalse("$reason must not be classified empty",
                EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = reason))
        }
        // Only the terminal "done" stops — plus a tool stop that delivered no
        // call ([T-android-tooluse-stop-no-calls]) — qualify. Pin the allow-list.
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, "stop"))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, "end_turn"))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, null))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, "tool_calls"))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, "tool_use"))
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, "TOOL_USE"))
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, "STOP"))
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(false, false, ""))
    }

    /**
     * The one-shot ceiling, end to end: reminder injected once; the retry is
     * empty AGAIN; the second decision must be GiveUp (error surfaced, no
     * loop), and the history still holds exactly ONE reminder.
     */
    @Test
    fun `a second empty turn after the reminder gives up - exactly one reminder ever lands`() {
        val history = mutableListOf(userText(REAL_CALLBACK), emptyAssistant)
        var fired = false
        val first = EmptyTurnRecovery.plan(history, alreadyFired = fired)
        assertTrue(first is EmptyTurnRecovery.Plan.NudgeExistingPart)
        fired = true
        applyPlan(history, first)
        assertEquals(1, reminderCount(history))

        // Retry round produced another empty assistant turn.
        history.add(emptyAssistant)
        val second = EmptyTurnRecovery.plan(history, alreadyFired = fired)
        assertEquals("guard must stop the loop", EmptyTurnRecovery.Plan.GiveUp, second)
        applyPlan(history, second)
        assertEquals("no second reminder", 1, reminderCount(history))
        // A tail that would otherwise be nudgeable still gives up once fired.
        assertEquals(EmptyTurnRecovery.Plan.GiveUp,
            EmptyTurnRecovery.plan(listOf(toolUse("c"), toolResult("c", "x"), emptyAssistant), alreadyFired = true))
    }

    /**
     * The reminder is an agentHistory-only mutation: it must not be written to
     * the DB and must not become a UI row. The loop code between the two plan
     * branches and the GiveUp branch performs no repository write and no
     * message-list publish. Source-grep drift guard.
     */
    @Test
    fun `the reminder is neither persisted nor shown - loop branches touch only agentHistory`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val start = vm.indexOf("is EmptyTurnRecovery.Plan.NudgeExistingPart -> {")
        val end = vm.indexOf("is EmptyTurnRecovery.Plan.GiveUp -> {", start)
        assertTrue("recovery branches not found in ChatViewModel", start > 0 && end > start)
        val branches = vm.substring(start, end)
        for (forbidden in listOf("chatRepository.", "appendMessage(", "_messages.", "updateAssistantMessage(", "insertMessage(")) {
            assertFalse("recovery branch must not call $forbidden", branches.contains(forbidden))
        }
        assertTrue("both branches mutate agentHistory only", branches.contains("agentHistory.removeAt(agentHistory.size - 1)"))
        assertTrue(branches.contains("EmptyTurnRecovery.REMINDER"))
        // Format: a <system-reminder> element, so the UI-side synthetic-row
        // filter (`trimStart().startsWith("<system-reminder>")`) and
        // stripSystemReminders both recognise it.
        assertTrue(EmptyTurnRecovery.REMINDER.trimStart().startsWith("<system-reminder>"))
        assertTrue(EmptyTurnRecovery.REMINDER.trimEnd().endsWith("</system-reminder>"))
        assertTrue(vm.contains("trimStart().startsWith(\"<system-reminder>\")"))
    }
}
