package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-tool-prefill] Source guards for the firing path.
 *
 * ChatViewModel needs a Context, a database and a live provider to construct,
 * so the path from a scheduled task to "the loop's first turn streams the
 * prefilled calls instead of asking the model" is pinned at the lines that
 * carry it. Each guard names the behaviour it protects; the pure pieces
 * (scripted chunk stream, storage, CLI parsing) are tested directly in
 * PrefilledToolCallTest and ScheduledPrefillCliTest.
 */
class ScheduledPrefillWiringTest {

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test
    fun `the runner stamps the envelope and passes the prefill to every prompting target`() {
        val src = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertTrue(src.contains("prefilledTool = prefill.firstOrNull()?.toolName,"))
        // NewSession / AppendToSession and ChildOfCurrent both prompt; rerun does not.
        assertEquals(2, Regex("""prefill = prefill,""").findAll(src).count())
    }

    @Test
    fun `the headless runner forwards the prefill into the send funnel`() {
        val src = ProductionSources.read("debug/HeadlessChatRunner.kt")
        assertTrue(src.contains("val outcome = vm.submitPrompt(text, prefill)"))
    }

    @Test
    fun `every way a prompt can reach the loop keeps its prefill`() {
        // Idle session: straight into the loop it starts.
        assertTrue(vm.contains("sendMessage(text, skipContextCheck = false, headless = true, prefill = prefill)"))
        // Busy session: rides the queue.
        assertTrue(vm.contains("QueuedPromptOrigin.USER, prefill)"))
        assertTrue(vm.contains("prefill = queued.flatMap { it.prefill },"))
        // Mid-loop injection re-arms the one-shot for the next turn.
        assertTrue(vm.contains("return InjectedTurn(newAssistantId, queued.flatMap { it.prefill })"))
        assertTrue(vm.contains("scriptedTurnFor(handled.prefill)?.let { pendingScriptedTurn = it }"))
        // Auto-compact before send parks it with the text and sends it after.
        assertTrue(vm.contains("pendingSendPrefill = prefill"))
        // f8cb6f989 threads `headless` through the same two calls; what this
        // pins is that the prefill still rides along.
        assertEquals(2, Regex("""sendMessage\(text, skipContextCheck = true, (headless = headless, )?prefill = prefill\)""").findAll(vm).count())
    }

    @Test
    fun `the first turn streams the scripted calls in place of the provider request`() {
        assertTrue(vm.contains("var pendingScriptedTurn: com.openminis.app.scheduled.ScriptedToolTurn? = scriptedTurnFor(prefill)"))
        val hook = vm.substringAfter("val scriptedTurn = pendingScriptedTurn?.takeUnless { helperWrapUpInjected }")
        // Consumed before the stream is built, so a later turn cannot replay it.
        assertTrue(hook.trimStart().startsWith("pendingScriptedTurn = null"))
        val branch = hook.substringBefore("turnChunks.collect { chunk ->")
        assertTrue(branch.contains("scriptedTurn.asStreamChunks()"))
        // The provider request, and its request-side bookkeeping, only happen
        // on the non-scripted branch.
        val elseBranch = branch.substringAfter("} else {")
        assertTrue(elseBranch.contains("currentProvider.streamMessage("))
        assertTrue(elseBranch.contains("recordContextDispatch(outboundHistory, currentProvider.model)"))
        assertTrue(elseBranch.contains("appendPersonaReminderToHistory(lastContextTokens)"))
    }

    @Test
    fun `there is exactly one chunk collector, shared by both branches`() {
        // The whole point of scripting the stream: a prefilled call goes
        // through the same tool card, dispatch and persistence code a model
        // call does. A second collector would be a second code path.
        assertEquals(1, Regex("""turnChunks\.collect \{ chunk ->""").findAll(vm).count())
        assertEquals(0, Regex("""\)\.collect \{ chunk ->""").findAll(vm.substringAfter("private suspend fun runAgentLoop(").substringBefore("private suspend fun executeTool(")).count())
    }

    @Test
    fun `prefilled calls are exempt from the loop detector pre-check`() {
        assertTrue(vm.contains("val precheck = if (com.openminis.app.scheduled.ScriptedToolTurn.isScriptedId(id)) {"))
    }

    @Test
    fun `the system prompt no longer says nothing can wake the model`() {
        // [T-android-parity-fixes] The shell bullet claimed `delay` was the ONLY
        // wait mechanism and that scheduling options "do not wake you", while
        // the minis-scheduled bullet below it described exactly that. Synced
        // with iOS 62286901a.
        assertFalse(vm.contains("`delay` is your ONLY wait mechanism"))
        assertFalse(vm.contains("they do not wake you"))
        // [T-prompt-cli-via-shell] CLI commands are phrased as "run X via
        // shell_execute", never as something to "use" like a tool (a model
        // once called minis-scheduled as a function tool: Unknown tool).
        assertTrue(vm.contains("For a check that should happen AFTER this turn ends, register it by running `minis-scheduled create …` via shell_execute"))
        assertTrue(vm.contains("either register a `minis-scheduled` follow-up (say so, with its task id)"))
        assertTrue(vm.contains("For follow-ups and recurring prompts, run `minis-scheduled create …` via shell_execute: it fires through a system alarm"))
        assertTrue(vm.contains("Android framework commands (available to run via shell_execute):"))
        assertFalse(vm.contains("Android-only tools (android-* CLIs)"))
        assertFalse(vm.contains("Do NOT use minis-scheduled"))
        assertFalse(vm.contains("Use `minis-model-use list`"))
    }

    @Test
    fun `the system prompt teaches the prefilled command flags`() {
        assertTrue(vm.contains("add `--command \"<shell command>\"` (optionally `--command-timeout 2m`)"))
        assertTrue(vm.contains("`--tool shell_execute --tool-args '{\"command\":\"…\"}'`"))
        assertTrue(vm.contains("you receive its real output as an already-completed tool call"))
    }

    @Test
    fun `the task editor keeps a prefill it cannot edit`() {
        val src = ProductionSources.read("ui/scheduled/ScheduledTaskEditScreen.kt")
        assertTrue(src.contains("prefillToolCall = existing.prefillToolCall"))
        assertTrue(src.contains("prefillToolCall = prefillToolCall.takeIf { targetMode !is ScheduledTargetMode.RerunMessage },"))
    }
}
