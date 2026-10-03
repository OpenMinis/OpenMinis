package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review 2026-09-23 — source invariants for the sub agent job lifecycle and
 * the queued-prompt hand-off inside ChatViewModel.
 *
 * Guards: af10e3af8 (run summary stamped BEFORE registry.finish, because
 * finish builds the callback synchronously), 06b7ad2e8 + bf74eae2a
 * (RUNNING must be backed by a live job — which only holds if every job is
 * eventually finished), 955e76d6a (mid-task queued message lost across the
 * inject path's suspend points).
 *
 * The functions involved are private suspend members of a ViewModel that
 * needs a Context, Room and a provider, so, like QueuedPromptInjectRaceTest
 * and EvictionGuardTest, the wiring is pinned as source facts. Two of these
 * fail on the current tree and are reported as bugs (see each KDoc).
 */
class Review0923SubAgentQueueInvariantsTest {

    private val vm: String by lazy { read("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }

    private fun read(path: String): String =
        File(path).also { assertTrue("missing ${it.absolutePath}", it.exists()) }.readText()

    /** Body of `fun <name>(` up to the next top-level member declaration. */
    private fun body(name: String): String {
        val start = vm.indexOf("fun $name(")
        assertTrue("function $name not found", start >= 0)
        val next = Regex("""\n    (private |internal |public )?(suspend )?fun \w+\(""")
            .find(vm, start + 10)?.range?.first ?: vm.length
        return vm.substring(start, next)
    }

    // ---- af10e3af8: summary before finish ----------------------------------

    /**
     * Every NON-failure `registry.finish` of a delegated run must be preceded
     * by `setSummaryLine` for the same job, or the completion callback card is
     * built without its "tools …" subline (finish -> runThen is synchronous;
     * the completion hook stamps the summary later on IO). Holds today for the
     * wait path, the background path and ScheduledAgentRunner.
     */
    @Test
    fun `every successful finish of a delegated run stamps the summary first`() {
        val files = listOf(
            "src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt",
            "src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt",
        )
        var checked = 0
        for (f in files) {
            val lines = read(f).lines()
            lines.forEachIndexed { i, line ->
                if (!line.contains("registry.finish(")) return@forEachIndexed
                val call = lines.subList(i, minOf(i + 3, lines.size)).joinToString(" ")
                if (call.contains("AgentJobState.FAILED") || call.contains("AgentJobState.CANCELLED")) return@forEachIndexed
                checked++
                val before = lines.subList(maxOf(0, i - 8), i).joinToString("\n")
                assertTrue(
                    "$f:${i + 1} finishes a run without setSummaryLine just before it",
                    before.contains("setSummaryLine("),
                )
            }
        }
        assertTrue("expected the wait, background and scheduled paths (found $checked)", checked >= 3)
    }

    // ---- resume path never closes its job ------------------------------------

    /**
     * [BUG] `resumeInterruptedChild` (ChatViewModel.kt ~2008-2105) registers a
     * job, marks it RUNNING and submits the resume notice — and then nothing
     * ever calls `registry.finish` for it on the success path. Every other
     * delegation path waits for the child to go idle (wait loop, or
     * startBackgroundHelper's `isStreaming.first { !it }`) and finishes the job.
     *
     * Result for a resumed sub agent that completes normally: the job stays
     * RUNNING for the life of the process — it keeps one of the
     * MAX_CONCURRENT_CHILD_JOBS slots, the parent never receives the final
     * <agent_callback>, the parent reads as `hasWorkInFlight()` forever (so the
     * VM cache can never evict it), and the delegate block keeps its RUNNING
     * spinner because OrphanedRunningToolGate sees a live job — the exact
     * idle-CPU drain 06b7ad2e8 fixed, re-created by a different path.
     *
     * Proposed fix: after submitPrompt succeeds, run the same completion tail
     * as startBackgroundHelper (wait for `isStreaming.first { !it }` with the
     * budget, stamp setSummaryLine, then registry.finish(DONE/FAILED/TIMEOUT)),
     * ideally by routing the resumed run through startBackgroundHelper.
     */
    @Test
    fun `a resumed sub agent run is finished when the child goes idle`() {
        val b = body("resumeInterruptedChild")
        assertTrue("sanity: this is the resume path", b.contains("resumeNotice()"))
        val waitsForIdle = b.contains("isStreaming.first { !it }") ||
            b.contains("startBackgroundHelper(") ||
            b.contains("setCompletionHook(")
        assertTrue(
            "resumeInterruptedChild must close its job when the child finishes; " +
                "today it only finishes on exception, so a resumed job stays RUNNING forever",
            waitsForIdle,
        )
    }

    // ---- 955e76d6a sibling: dequeue before persist ---------------------------

    /**
     * [BUG] `injectQueuedPromptsAsNewTurn` empties `_promptQueue` on entry and
     * only persists the combined user row after four suspension points
     * (ensureSession, prepareUserAttachments, buildPastedParts,
     * appendMessage) — the same ~5 s window 955e76d6a measured. If the user
     * taps Stop inside that window, streamJob is cancelled at the next
     * suspension; `cancelStream` then finds `_promptQueue` empty, so
     * `resumeQueueAfterCancel` never runs. The queued message is neither sent
     * nor persisted, and its dashed "queued" bubble stays on screen until the
     * session is reopened, where it silently disappears. cancelStream's own
     * contract (T189) is that queued prompts survive a Stop.
     *
     * Proposed fix: take the prompts off the queue only after appendMessage
     * returns, or wrap the persist section in
     * `catch (e: CancellationException) { _promptQueue.value = queued + _promptQueue.value; throw e }`.
     * `drainQueuedPrompts` has the same dequeue-first shape.
     */
    @Test
    fun `the mid-task inject path cannot lose queued prompts to a Stop`() {
        // [T-queue-dequeue-after-persist] Fixed by dequeuing (by id) only after
        // appendMessage returns; pinned for both the inject and the drain path.
        for (name in listOf("injectQueuedPromptsAsNewTurn", "drainQueuedPrompts")) {
            val b = body(name)
            assertTrue("$name: the queue must not be emptied wholesale", !b.contains("_promptQueue.value = emptyList()"))
            val persist = b.indexOf("chatRepository.appendMessage(")
            val dequeue = b.indexOf("_promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }", persist)
            assertTrue("$name: sanity: persist present", persist >= 0)
            val restoresOnCancel = Regex("""catch \([^)]*CancellationException[^)]*\)[^}]*_promptQueue""")
                .containsMatchIn(b)
            assertTrue(
                "$name: queue is dequeued before the suspending persist and never restored on cancellation",
                dequeue > persist || restoresOnCancel,
            )
        }
    }

    /** Pins the 955e76d6a fix itself: the emit filters the LIVE list. */
    @Test
    fun `the inject emit filters the live message list, not a snapshot`() {
        val b = body("injectQueuedPromptsAsNewTurn")
        assertTrue(b.contains("_messages.value = _messages.value.filterNot"))
        assertTrue("no stale snapshot variable", !b.contains("msgsAfterUnqueue ="))
    }
}
